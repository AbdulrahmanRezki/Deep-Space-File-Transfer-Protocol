import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.io.FileInputStream;
public class Sender {
    private static final int MODULO = 128;
    private static final int MAX_PAYLOAD = 124;

    public static void main(String args[]) {
        if (args.length < 5 || args.length > 6) {
            System.err.println("Incorrect input formatting");
            System.exit(1);
        }
        String rcv_ip = args[0];
        int rcv_data_port = Integer.parseInt(args[1]);
        int sender_ack_port = Integer.parseInt(args[2]);
        String input_file = args[3];
        int timeout_ms = Integer.parseInt(args[4]);
        int window_size = -1;
        if (args.length == 6) {
            window_size = Integer.parseInt(args[5]);
        }
        try (DatagramSocket socket = new DatagramSocket(sender_ack_port)) {
            socket.setSoTimeout(timeout_ms);
            InetAddress rcv_address = InetAddress.getByName(rcv_ip);
            if (window_size == -1) {
                run_stop_and_wait(socket, rcv_address, rcv_data_port, input_file, timeout_ms);
            } else {
                run_GBN(socket, rcv_address, rcv_data_port, input_file, window_size, timeout_ms);
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
    private static void run_stop_and_wait(DatagramSocket socket, InetAddress rcv_address, int rcv_data_port, String input_file, int timeout_ms) throws Exception {
        // Handshake
        long start_time = System.nanoTime();
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        send_packet(socket, rcv_address, rcv_data_port, sot);
        System.out.println("Sender: SOT sent");
        DSPacket ack = receive_packet(socket);
        if (ack.getType() != DSPacket.TYPE_ACK || ack.getSeqNum() != 0) {
            System.err.println("Sender: Expected ACK 0, got something else");
            System.exit(1);
        }
        System.out.println("Sender: ACK 0 received");
        // Data Transfer
        FileInputStream fis = new FileInputStream(input_file);
        int seq_num = 1;
        byte[] buf = new byte[MAX_PAYLOAD];
        int bytes_read;
        while ((bytes_read = fis.read(buf)) != -1) {
            byte[] payload = new byte[bytes_read];
            System.arraycopy(buf, 0, payload, 0, bytes_read);
            DSPacket data_packet = new DSPacket(DSPacket.TYPE_DATA, seq_num, payload);
            int timeout_count = 0;
            boolean acked = false;
            while (!acked) {
                send_packet(socket, rcv_address, rcv_data_port, data_packet);
                System.out.println("Sender: Sent DATA seq=" + seq_num);
                try {
                    DSPacket response = receive_packet(socket);
                    if (response.getType() == DSPacket.TYPE_ACK && response.getSeqNum() == seq_num) {
                        System.out.println("Sender: ACK " + seq_num + " received");
                        timeout_count = 0;
                        acked = true;
                    }
                }
                catch (java.net.SocketTimeoutException e) {
                    timeout_count++;
                    System.out.println("Sender: Timeout #" + timeout_count + " for seq=" + seq_num);
                    if (timeout_count >= 3) {
                        System.err.println("Sender: Unable to transfer file");
                        fis.close();
                        System.exit(1);
                    }
                }
            }
            seq_num = (seq_num + 1) % MODULO;
        }
        fis.close();
        // Teardown
        double elapsed = (System.nanoTime() - start_time) / 1000000000.0;
        System.out.printf("Total Transmission Time: %.2f seconds%n", elapsed);
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, seq_num, null);
        for (int i = 0; i < 3; i++) {
            send_packet(socket, rcv_address, rcv_data_port, eot);
            System.out.println("Sender: EOT sent");
            try {
                DSPacket eot_ack = receive_packet(socket);
                if (eot_ack.getType() == DSPacket.TYPE_ACK && eot_ack.getSeqNum() == seq_num) {
                    System.out.println("Sender: EOT acknowledged.");
                    break;
                }
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("Sender: EOT timeout #" + (i + 1));
            }
        }
        System.out.println("Sender: Transfer complete.");
    }
    private static void run_GBN(DatagramSocket socket, InetAddress rcv_address, int rcv_data_port, String input_file, int window_size, int timeout_ms) throws Exception {
        // Handshake
        long start_time = System.nanoTime();
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        send_packet(socket, rcv_address, rcv_data_port, sot);
        System.out.println("Sender: SOT sent");
        DSPacket ack = receive_packet(socket);
        if (ack.getType() != DSPacket.TYPE_ACK || ack.getSeqNum() != 0) {
            System.err.println("Sender: Expected ACK 0, got something else");
            System.exit(1);
        }
        System.out.println("Sender: ACK 0 received");
        // Load File
        java.util.List<DSPacket> all_packets = new java.util.ArrayList<>();
        FileInputStream fis = new FileInputStream(input_file);
        byte[] buf = new byte[MAX_PAYLOAD];
        int bytes_read;
        int seq_num = 1;
        while ((bytes_read = fis.read(buf)) != -1) {
            byte[] payload = new byte[bytes_read];
            System.arraycopy(buf, 0, payload, 0, bytes_read);
            all_packets.add(new DSPacket(DSPacket.TYPE_DATA, seq_num, payload));
            seq_num = (seq_num + 1) % MODULO;
        }
        fis.close();
        // Data Transfer
        int base = 0;
        int next_index = 0;
        int timeout_count = 0;
        int total_packets = all_packets.size();
        while (base < total_packets) {
            while (next_index < base + window_size && next_index < total_packets) {
                java.util.List<DSPacket> group = new java.util.ArrayList<>();
                for (int i = next_index; i < Math.min(next_index + 4, total_packets); i++) {
                    group.add(all_packets.get(i));
                }
                java.util.List<DSPacket> to_send = ChaosEngine.permutePackets(group);
                for (DSPacket p : to_send) {
                    send_packet(socket, rcv_address, rcv_data_port, p);
                    System.out.println("Sender: Sent data seq=" + p.getSeqNum());
                }
                next_index += group.size();
            }
            try {
                DSPacket response = receive_packet(socket);
                if (response.getType() == DSPacket.TYPE_ACK) {
                    int ack_seq = response.getSeqNum();
                    System.out.println("Sender: ACK received seq=" + ack_seq);
                    for (int i = base; i < Math.min(base + window_size, total_packets); i++) {
                        if (all_packets.get(i).getSeqNum() == ack_seq) {
                            base = i + 1;
                            timeout_count = 0;
                            break;
                        }
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                timeout_count++;
                System.out.println("Sender: Timeout #" + timeout_count + " retransmitting from base=" + base);
                if (timeout_count >= 3) {
                    System.err.println("Sender: Unable to transfer file");
                    System.exit(1);
                }
                next_index = base;
            }
        }
        // Teardown
        double elapsed = (System.nanoTime() - start_time) / 1000000000.0;
        System.out.printf("Total Transmission Time: %.2f seconds%n", elapsed);
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, seq_num, null);
        for (int i = 0; i < 3; i++) {
            send_packet(socket, rcv_address, rcv_data_port, eot);
            System.out.println("Sender: EOT sent");
            try {
                DSPacket eot_ack = receive_packet(socket);
                if (eot_ack.getType() == DSPacket.TYPE_ACK && eot_ack.getSeqNum() == seq_num) {
                    System.out.println("Sender: EOT acknowledged.");
                    break;
                }
            } catch (java.net.SocketTimeoutException e) {
                System.out.println("Sender: EOT timeout #" + (i + 1));
            }
        }
        System.out.println("Sender: Transfer complete.");
    }
    private static void send_packet(DatagramSocket socket, InetAddress rcv_address, int rcv_data_port, DSPacket packet) throws Exception {
        byte[] data = packet.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, rcv_address, rcv_data_port);
        socket.send(dp);
    }
    private static DSPacket receive_packet(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new DSPacket(dp.getData());
    }
}