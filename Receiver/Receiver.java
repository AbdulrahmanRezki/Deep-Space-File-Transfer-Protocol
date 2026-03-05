import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * DS-FTP Receiver (Earth Station)
 *
 * Supports both Stop-and-Wait (RDT 3.0) and Go-Back-N (GBN) protocols.
 * Mode is determined automatically based on whether the Sender uses a window.
 * The Receiver always handles both modes correctly.
 *
 * Usage:
 *   java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
 */
public class Receiver {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int MODULO      = 128;
    private static final int SOCKET_TIMEOUT_MS = 60000; // 60s idle timeout

    // -----------------------------------------------------------------------
    // Entry Point
    // -----------------------------------------------------------------------
    public static void main(String[] args) {
        if (args.length != 5) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> " +
                               "<rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }

        String   senderIp      = args[0];
        int      senderAckPort = Integer.parseInt(args[1]);
        int      rcvDataPort   = Integer.parseInt(args[2]);
        String   outputFile    = args[3];
        int      rn            = Integer.parseInt(args[4]);

        System.out.println("[Receiver] Starting up.");
        System.out.println("[Receiver] Listening on port : " + rcvDataPort);
        System.out.println("[Receiver] Sending ACKs to   : " + senderIp + ":" + senderAckPort);
        System.out.println("[Receiver] Output file       : " + outputFile);
        System.out.println("[Receiver] Reliability Number: " + rn);

        try (DatagramSocket socket = new DatagramSocket(rcvDataPort);
             FileOutputStream fos  = new FileOutputStream(outputFile)) {

            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            InetAddress senderAddr = InetAddress.getByName(senderIp);

            // ---------------------------------------------------------------
            // PHASE 1: Handshake – wait for SOT
            // ---------------------------------------------------------------
            int ackCount = 0; // 1-indexed ACK counter for ChaosEngine

            System.out.println("[Receiver] Waiting for SOT...");
            DSPacket sotPacket = receivePacket(socket);

            if (sotPacket.getType() != DSPacket.TYPE_SOT || sotPacket.getSeqNum() != 0) {
                System.err.println("[Receiver] Expected SOT(0), got type=" +
                                   sotPacket.getType() + " seq=" + sotPacket.getSeqNum());
                System.exit(1);
            }

            System.out.println("[Receiver] SOT received. Sending ACK 0.");
            ackCount++;
            sendAck(socket, senderAddr, senderAckPort, 0, ackCount, rn);

            // ---------------------------------------------------------------
            // PHASE 2: Data Transfer
            //   We do NOT know in advance whether the Sender uses SAW or GBN.
            //   Both modes share the same receiver logic: buffer out-of-order
            //   packets within the window, deliver in order, send cumulative ACKs.
            //   For Stop-and-Wait the effective window is 1, so buffering is a no-op.
            //   We discover the window dynamically (we accept packets that are
            //   within [expectedSeq, expectedSeq + MODULO/2) modulo 128, which
            //   is the safe GBN receive window upper bound).
            // ---------------------------------------------------------------

            // expectedSeq starts at 1 (first DATA packet)
            int expectedSeq = 1;

            // lastAckedSeq: the highest contiguous seq we've delivered so far.
            // We ACK this value cumulatively.
            // Initialise to 0 (the SOT seq) so first real ACK is seq 1.
            int lastDeliveredSeq = 0;

            // Out-of-order buffer: seqNum -> DSPacket
            Map<Integer, DSPacket> buffer = new HashMap<>();

            boolean transferDone = false;

            while (!transferDone) {
                DSPacket pkt = receivePacket(socket);

                byte type = pkt.getType();
                int  seq  = pkt.getSeqNum();

                // ---- EOT -------------------------------------------------------
                if (type == DSPacket.TYPE_EOT) {
                    System.out.println("[Receiver] EOT received (seq=" + seq + "). Teardown.");
                    ackCount++;
                    sendAck(socket, senderAddr, senderAckPort, seq, ackCount, rn);
                    transferDone = true;
                    break;
                }

                // ---- Unexpected non-DATA packet --------------------------------
                if (type != DSPacket.TYPE_DATA) {
                    System.out.println("[Receiver] Ignoring unexpected packet type=" + type);
                    continue;
                }

                // ---- DATA packet -----------------------------------------------
                // Check if seq is within the receive window.
                // Window: [expectedSeq, expectedSeq + MODULO/2) mod 128
                if (isWithinWindow(seq, expectedSeq)) {
                    // Buffer the packet (ignore duplicates already buffered)
                    if (!buffer.containsKey(seq)) {
                        buffer.put(seq, pkt);
                        System.out.println("[Receiver] Buffered DATA seq=" + seq);
                    } else {
                        System.out.println("[Receiver] Duplicate (already buffered) seq=" + seq + ", ignoring payload.");
                    }

                    // Deliver as many in-order packets as possible
                    while (buffer.containsKey(expectedSeq)) {
                        DSPacket inOrder = buffer.remove(expectedSeq);
                        fos.write(inOrder.getPayload(), 0, inOrder.getLength());
                        System.out.println("[Receiver] Delivered seq=" + expectedSeq +
                                           " (" + inOrder.getLength() + " bytes)");
                        lastDeliveredSeq = expectedSeq;
                        expectedSeq      = (expectedSeq + 1) % MODULO;
                    }

                    // Send cumulative ACK for lastDeliveredSeq
                    ackCount++;
                    System.out.println("[Receiver] Sending cumulative ACK=" + lastDeliveredSeq);
                    sendAck(socket, senderAddr, senderAckPort, lastDeliveredSeq, ackCount, rn);

                } else {
                    // Packet is below window (duplicate) or above window (too far ahead)
                    // Re-send the last cumulative ACK
                    System.out.println("[Receiver] Out-of-window seq=" + seq +
                                       " (expected=" + expectedSeq + "). Re-ACKing " + lastDeliveredSeq);
                    ackCount++;
                    sendAck(socket, senderAddr, senderAckPort, lastDeliveredSeq, ackCount, rn);
                }
            }

            // ---------------------------------------------------------------
            // PHASE 3: Teardown complete
            // ---------------------------------------------------------------
            fos.flush();
            System.out.println("[Receiver] File written successfully to: " + outputFile);
            System.out.println("[Receiver] Shutting down.");

        } catch (IOException e) {
            System.err.println("[Receiver] Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // Helper: receive one DSPacket from the socket
    // -----------------------------------------------------------------------
    private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
        byte[]         buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp  = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new DSPacket(dp.getData());
    }

    // -----------------------------------------------------------------------
    // Helper: build and (maybe) send an ACK, respecting ChaosEngine drop rule
    // -----------------------------------------------------------------------
    private static void sendAck(DatagramSocket socket,
                                 InetAddress    dest,
                                 int            destPort,
                                 int            seq,
                                 int            ackCount,
                                 int            rn) throws IOException {

        DSPacket ack    = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[]   bytes  = ack.toBytes();

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[Receiver] ChaosEngine DROPPED ACK seq=" + seq +
                               " (ackCount=" + ackCount + ")");
            return;
        }

        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, dest, destPort);
        socket.send(dp);
        System.out.println("[Receiver] Sent ACK seq=" + seq);
    }

    // -----------------------------------------------------------------------
    // Helper: check whether a sequence number falls inside the receive window.
    //
    // The receive window is [expectedSeq, expectedSeq + HALF) mod 128,
    // where HALF = 64.  This is the standard GBN safe window.
    // For Stop-and-Wait (window=1) this is equivalent to checking seq == expectedSeq.
    // -----------------------------------------------------------------------
    private static boolean isWithinWindow(int seq, int expectedSeq) {
        // Distance from expectedSeq forward (mod 128)
        int dist = (seq - expectedSeq + MODULO) % MODULO;
        return dist < (MODULO / 2); // within first half of the number space
    }
}
