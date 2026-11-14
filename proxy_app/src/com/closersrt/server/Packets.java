package com.closersrt.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * IPv4 / TCP / UDP packet construction for the tun relay.
 *
 * Deliberately free of Android imports so it can be compiled and exercised with
 * a plain desktop JDK — the relay's correctness is the highest-risk part of the
 * local-proxy design, and being able to verify checksums independently (see
 * tools/verify_packets.py) is worth the extra file.
 *
 * `main()` emits sample packets as hex for that external verifier.
 *
 * No nested or anonymous classes anywhere: R8/D8 aborts on non-static inner
 * classes (see proxy_app/README.md), and plain static methods keep this file
 * trivially testable.
 */
public final class Packets {

    public static final int IPV4 = 20;
    public static final int TCP = 20;
    public static final int UDP_HDR = 8;

    private Packets() { }

    // ------------------------------------------------------------------
    // checksums
    // ------------------------------------------------------------------

    /** Standard internet checksum (RFC 1071) over a byte range. */
    public static short checksum(byte[] data, int off, int len) {
        long sum = 0;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (data[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (short) ~sum;
    }

    /** TCP checksum, including the IPv4 pseudo-header. */
    public static short tcpChecksum(byte[] pkt, int payloadLen) {
        int tcpLen = TCP + payloadLen;
        byte[] pseudo = new byte[12 + tcpLen];
        System.arraycopy(pkt, 12, pseudo, 0, 8);              // src + dst address
        pseudo[8] = 0;
        pseudo[9] = 6;                                       // protocol TCP
        pseudo[10] = (byte) ((tcpLen >> 8) & 0xFF);
        pseudo[11] = (byte) (tcpLen & 0xFF);
        System.arraycopy(pkt, IPV4, pseudo, 12, tcpLen);
        pseudo[12 + 16] = 0;                                 // zero the field
        pseudo[12 + 17] = 0;
        return checksum(pseudo, 0, pseudo.length);
    }

    /** UDP checksum, including the IPv4 pseudo-header. Zero maps to 0xFFFF. */
    public static short udpChecksum(byte[] pkt, int payloadLen) {
        int udpLen = UDP_HDR + payloadLen;
        byte[] pseudo = new byte[12 + udpLen];
        System.arraycopy(pkt, 12, pseudo, 0, 8);
        pseudo[8] = 0;
        pseudo[9] = 17;                                      // protocol UDP
        pseudo[10] = (byte) ((udpLen >> 8) & 0xFF);
        pseudo[11] = (byte) (udpLen & 0xFF);
        System.arraycopy(pkt, IPV4, pseudo, 12, udpLen);
        pseudo[12 + 6] = 0;
        pseudo[12 + 7] = 0;
        short c = checksum(pseudo, 0, pseudo.length);
        return c == 0 ? (short) 0xFFFF : c;
    }

    // ------------------------------------------------------------------
    // builders
    // ------------------------------------------------------------------

    public static byte[] buildTcp(String srcIp, int srcPort, String dstIp, int dstPort,
                                  int seq, long ack, int flags, byte[] payload) {
        byte[] pkt = new byte[IPV4 + TCP + payload.length];
        ByteBuffer b = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN);
        b.put(0, (byte) 0x45);                       // version 4, IHL 5
        b.putShort(2, (short) pkt.length);           // total length
        b.putShort(4, (short) 0);                    // identification
        b.putShort(6, (short) 0x4000);               // don't fragment
        b.put(8, (byte) 64);                         // TTL
        b.put(9, (byte) 6);                          // protocol TCP
        putIp(b, 12, srcIp);
        putIp(b, 16, dstIp);
        b.putShort(IPV4, (short) srcPort);
        b.putShort(IPV4 + 2, (short) dstPort);
        b.putInt(IPV4 + 4, seq);
        b.putInt(IPV4 + 8, (int) ack);
        b.put(IPV4 + 12, (byte) 0x50);               // data offset 5, reserved 0
        b.put(IPV4 + 13, (byte) flags);
        b.putShort(IPV4 + 14, (short) 65535);        // window
        b.putShort(IPV4 + 16, (short) 0);            // checksum (filled below)
        b.putShort(IPV4 + 18, (short) 0);            // urgent pointer
        System.arraycopy(payload, 0, pkt, IPV4 + TCP, payload.length);
        b.putShort(10, checksum(pkt, 0, IPV4));                 // IP header csum
        b.putShort(IPV4 + 16, tcpChecksum(pkt, payload.length));
        return pkt;
    }

    public static byte[] buildUdp(String srcIp, int srcPort, String dstIp, int dstPort,
                                  byte[] payload) {
        int udpLen = UDP_HDR + payload.length;
        byte[] pkt = new byte[IPV4 + udpLen];
        ByteBuffer b = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN);
        b.put(0, (byte) 0x45);
        b.putShort(2, (short) pkt.length);
        b.putShort(4, (short) 0);
        b.putShort(6, (short) 0x4000);
        b.put(8, (byte) 64);
        b.put(9, (byte) 17);                         // protocol UDP
        putIp(b, 12, srcIp);
        putIp(b, 16, dstIp);
        b.putShort(IPV4, (short) srcPort);
        b.putShort(IPV4 + 2, (short) dstPort);
        b.putShort(IPV4 + 4, (short) udpLen);
        b.putShort(IPV4 + 6, (short) 0);             // checksum (filled below)
        System.arraycopy(payload, 0, pkt, IPV4 + UDP_HDR, payload.length);
        b.putShort(IPV4 + 6, udpChecksum(pkt, payload.length));
        b.putShort(10, checksum(pkt, 0, IPV4));
        return pkt;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    public static void putIp(ByteBuffer b, int off, String ip) {
        String[] parts = ip.split("\\.");
        for (int i = 0; i < 4; i++) {
            b.put(off + i, (byte) Integer.parseInt(parts[i]));
        }
    }

    public static String ip(byte[] p, int off) {
        return (p[off] & 0xFF) + "." + (p[off + 1] & 0xFF) + "."
                + (p[off + 2] & 0xFF) + "." + (p[off + 3] & 0xFF);
    }

    public static int u16(byte[] p, int off) {
        return ((p[off] & 0xFF) << 8) | (p[off + 1] & 0xFF);
    }

    public static long u32(byte[] p, int off) {
        return ((long) (p[off] & 0xFF) << 24) | ((p[off + 1] & 0xFF) << 16)
                | ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Emits test vectors as hex on stdout, tagged one per line, so an
     * independent implementation can verify them:
     *
     *     java -cp <classes> com.closersrt.server.Packets
     */
    public static void main(String[] args) {
        // 1. plain internet checksum over a known block
        byte[] block = new byte[20];
        for (int i = 0; i < block.length; i++) block[i] = (byte) (i + 1);
        System.out.println("checksum_block=" + hex(new byte[]{
                (byte) (checksum(block, 0, block.length) >> 8),
                (byte) checksum(block, 0, block.length)}));

        // 2. a SYN
        System.out.println("syn=" + hex(buildTcp("10.9.9.1", 40000,
                "175.106.101.33", 8500, 0x11223344, 0, 0x02, new byte[0])));

        // 3. a SYN-ACK
        System.out.println("synack=" + hex(buildTcp("175.106.101.33", 8500,
                "10.9.9.1", 40000, 0x55667788, 0x11223345, 0x12, new byte[0])));

        // 4. data with payload
        byte[] payload = "HELLO-CLOSERS".getBytes();
        System.out.println("psh=" + hex(buildTcp("175.106.101.33", 8500,
                "10.9.9.1", 40000, 0x55667788, 0x11223345, 0x18, payload)));

        // 5. odd-length payload, to exercise the tail-byte path of the checksum
        byte[] odd = "ABC".getBytes();
        System.out.println("odd=" + hex(buildTcp("175.106.101.33", 8500,
                "10.9.9.1", 40000, 0x55667788, 0x11223345, 0x18, odd)));

        // 6. UDP datagram
        byte[] udp = "PING".getBytes();
        System.out.println("udp=" + hex(buildUdp("10.9.9.9", 53,
                "10.9.9.1", 40000, udp)));

        // 7. the DNS answer we would send for a query
        byte[] query = new byte[]{
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x0d, 'c','l','o','s','e','r','s','r','t','.','c','o','m',
                0x00, 0x00, 0x01, 0x00, 0x01};
        byte[] answer = Dns.answer(query, "10.9.9.8");
        System.out.println("dns_query=" + hex(query));
        System.out.println("dns_answer=" + (answer == null ? "null" : hex(answer)));
    }
}
