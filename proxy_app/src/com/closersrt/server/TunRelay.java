package com.closersrt.server;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A minimal userspace TCP/IP relay for the tun device.
 *
 * A VpnService hands us raw IP packets; to let the game talk to our server we
 * have to terminate TCP ourselves.  This is the same job tun2socks does, trimmed
 * to what this protocol needs (one long-lived connection with small
 * request/response exchanges), so it deliberately skips:
 *
 *   * TCP options (window scaling, SACK, timestamps)
 *   * out-of-order reassembly and retransmission
 *   * fragmentation
 *
 * It does implement: SYN/SYN-ACK/ACK handshake, payload relay in both
 * directions, FIN/RST teardown, correct IPv4 + TCP checksums, and a DNS
 * responder so every hostname resolves to one address we can terminate.
 */
public final class TunRelay {

    /** Exempts a socket from the tunnel (VpnService.protect). */
    public interface SocketProtector {
        void protect(Socket s);
        void protectDatagram(java.net.DatagramSocket s);
    }

    /** Where we forward every DNS query we do not answer ourselves. */
    private static final String UPSTREAM_DNS = "8.8.8.8";

    private static final AtomicInteger FLOWS = new AtomicInteger();
    private static final AtomicLong PKT_IN = new AtomicLong();
    private static final AtomicLong PKT_OUT = new AtomicLong();

    /**
     * Upstream DNS lookups run here rather than on the tun reading thread: a
     * slow resolver must never stall packet processing for everything else.
     */
    private static final java.util.concurrent.ExecutorService FORWARD_POOL =
            java.util.concurrent.Executors.newCachedThreadPool();

    public static int flowCount() { return FLOWS.get(); }
    public static long packetsIn() { return PKT_IN.get(); }
    public static long packetsOut() { return PKT_OUT.get(); }

    private static final int IPV4 = 20;
    private static final int TCP = 20;
    private static final int UDP_HDR = 8;

    private final FileInputStream in;
    private final FileOutputStream out;
    private final SocketProtector protector;
    private final Map<String, Flow> flows = new ConcurrentHashMap<String, Flow>();

    public TunRelay(FileInputStream in, FileOutputStream out, SocketProtector p) {
        this.in = in;
        this.out = out;
        this.protector = p;
    }

    public void loop() {
        byte[] buf = new byte[32767];
        while (true) {
            int n;
            try {
                n = in.read(buf);
            } catch (Exception e) {
                Status.log("tun read ended: " + e);
                return;
            }
            if (n <= 0) continue;
            PKT_IN.incrementAndGet();
            try {
                handlePacket(buf, n);
            } catch (Exception e) {
                Status.log("packet error: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    private void handlePacket(byte[] p, int len) throws Exception {
        if (len < IPV4) return;
        int version = (p[0] >> 4) & 0xF;
        if (version != 4) return;
        int ihl = (p[0] & 0xF) * 4;
        if (ihl < IPV4 || len < ihl) return;
        int proto = p[9] & 0xFF;
        String srcIp = Packets.ip(p, 12);
        String dstIp = Packets.ip(p, 16);

        if (proto == 17) {                       // UDP
            handleUdp(p, len, ihl, srcIp, dstIp);
        } else if (proto == 6) {                 // TCP
            handleTcp(p, len, ihl, srcIp, dstIp);
        }
    }

    // ---- DNS ----------------------------------------------------------
    private void handleUdp(byte[] p, int len, int ihl, String srcIp, String dstIp) throws Exception {
        int udpOff = ihl;
        if (len < udpOff + UDP_HDR) return;
        int srcPort = Packets.u16(p, udpOff);
        int dstPort = Packets.u16(p, udpOff + 2);
        int udpLen = Packets.u16(p, udpOff + 4);
        if (dstPort != 53) {
            Status.log("udp " + srcIp + ":" + srcPort + " -> " + dstIp + ":" + dstPort
                    + " (" + Math.max(0, udpLen - UDP_HDR) + "B) ignored");
            return;
        }
        byte[] query = Arrays.copyOfRange(p, udpOff + UDP_HDR,
                Math.min(len, udpOff + udpLen));
        String host = hostOf(query);

        // Decide: answer it ourselves, or forward to the real resolver.
        if (isOurs(host)) {
            byte[] answer = Dns.answer(query, ProxyVpnService.PATCH_IP);
            if (answer == null) {
                Status.log("DNS query not understood (" + query.length + "B)");
                return;
            }
            // The reply must travel in the OPPOSITE direction to the query:
            // source = the server we pretended to be (the query's destination),
            // destination = the client that asked (the query's source).
            byte[] pkt = Packets.buildUdp(dstIp, dstPort, srcIp, srcPort, answer);
            Status.log("DNS " + host + " -> " + ProxyVpnService.PATCH_IP
                    + "  (answered locally)");
            write(pkt);
            return;
        }

        // Everything else is a live service (GamePot, Google, ...): forward the
        // query upstream and relay the real answer.  Answering these with a fake
        // address is what broke the retail login earlier, so it must not happen.
        final byte[] q = query;
        final String sIp = srcIp, dIp = dstIp;
        final int sPort = srcPort, dPort = dstPort;
        FORWARD_POOL.execute(new ForwardTask(this, q, sIp, sPort, dIp, dPort, host));
    }

    /** True when this hostname is one we deliberately take over. */
    private static boolean isOurs(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String mine : ProxyVpnService.PATCH_HOSTS) {
            if (h.equals(mine) || h.endsWith("." + mine)) return true;
        }
        return false;
    }

    /** One upstream DNS exchange, off the tun reading thread. */
    private static final class ForwardTask implements Runnable {
        private final byte[] query;
        private final String srcIp, dstIp, host;
        private final int srcPort, dstPort;
        private final TunRelay relay;

        ForwardTask(TunRelay relay, byte[] query, String srcIp, int srcPort,
                    String dstIp, int dstPort, String host) {
            this.relay = relay;
            this.query = query;
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
            this.host = host;
        }

        @Override public void run() {
            java.net.DatagramSocket sock = null;
            try {
                sock = new java.net.DatagramSocket();
                relay.protector.protectDatagram(sock);
                sock.setSoTimeout(4000);
                java.net.InetAddress up = java.net.InetAddress.getByName(UPSTREAM_DNS);
                sock.send(new java.net.DatagramPacket(query, query.length, up, 53));
                byte[] buf = new byte[2048];
                java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
                sock.receive(resp);
                byte[] answer = Arrays.copyOf(buf, resp.getLength());
                relay.write(Packets.buildUdp(dstIp, dstPort, srcIp, srcPort, answer));
            } catch (Exception e) {
                Status.log("DNS forward failed for " + host + ": " + e);
            } finally {
                if (sock != null) sock.close();
            }
        }
    }

    /** Best-effort hostname extraction, for readable logs only. */
    private static String hostOf(byte[] query) {
        try {
            int off = 12;
            StringBuilder sb = new StringBuilder();
            while (off < query.length && (query[off] & 0xFF) != 0) {
                int n = query[off] & 0xFF;
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(query, off + 1, n, "US-ASCII"));
                off += 1 + n;
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- TCP ----------------------------------------------------------
    private void handleTcp(byte[] p, int len, int ihl, String srcIp, String dstIp) throws Exception {
        int t = ihl;
        if (len < t + TCP) return;
        int srcPort = Packets.u16(p, t);
        int dstPort = Packets.u16(p, t + 2);
        long seq = Packets.u32(p, t + 4);
        long ack = Packets.u32(p, t + 8);
        int dataOff = ((p[t + 12] >> 4) & 0xF) * 4;
        int flags = p[t + 13] & 0xFF;
        int payloadOff = t + dataOff;
        int payloadLen = Math.max(0, len - payloadOff);
        byte[] payload = payloadLen > 0
                ? Arrays.copyOfRange(p, payloadOff, payloadOff + payloadLen) : new byte[0];

        boolean syn = (flags & 0x02) != 0;
        boolean fin = (flags & 0x01) != 0;
        boolean rst = (flags & 0x04) != 0;
        boolean psh = (flags & 0x08) != 0;

        String key = srcIp + ":" + srcPort + ">" + dstIp + ":" + dstPort;
        Flow f = flows.get(key);

        // Log EVERY packet on the game flow, ACK-only ones included.
        //
        // Without this we only see packets that carry payload, so there is no
        // way to tell whether the client's kernel acknowledged the 24-byte
        // SC_LOGINOPENRES we send.  An ACK for it proves the kernel accepted the
        // segment and the silence is in the application; no ACK means the
        // segment never landed.
        if (dstPort == GameServer.PORT) {
            Status.log("tcp rx flags=0x" + Integer.toHexString(flags)
                    + " seq=0x" + Integer.toHexString((int) seq)
                    + " ack=0x" + Integer.toHexString((int) ack)
                    + " len=" + payloadLen);
        }

        if (syn && f == null) {
            int localPort = localPortFor(dstIp, dstPort);
            boolean passthrough = localPort == PASSTHROUGH;
            if (localPort <= 0 && !passthrough) {
                Status.log("tcp " + key + " SYN -> no local server for port " + dstPort
                        + " (sending RST)");
                write(Packets.buildTcp(dstIp, dstPort, srcIp, srcPort, 1, seq + 1, 0x14, new byte[0]));
                return;
            }
            Socket s = new Socket();
            protector.protect(s);
            s.setTcpNoDelay(true);
            if (passthrough) {
                // Not one of ours: this is a live service (GamePot's login API,
                // Firebase, ...).  Connect to the real destination and relay the
                // bytes untouched.  Terminating these locally is what made the
                // guest login fail with
                //
                //     CertificateException: Trust anchor for certification path not found
                //     GAMEPOT: initialize fail! {"code":4002,...}
                //
                // because the client was handed our self-signed patch
                // certificate for a host we do not own.
                s.connect(new InetSocketAddress(dstIp, dstPort), 8000);
                Status.log("tcp " + key + " SYN -> passthrough " + dstIp + ":" + dstPort);
            } else {
                s.connect(new InetSocketAddress("127.0.0.1", localPort), 8000);
            }
            f = new Flow(key, s, dstIp, dstPort, srcIp, srcPort);
            f.theirSeq = seq + 1;
            f.ourSeq = (int) (System.nanoTime() & 0x7FFFFFFF);
            flows.put(key, f);
            FLOWS.set(flows.size());
            Status.log("tcp " + key + " SYN -> 127.0.0.1:" + localPort + " (" + f.id + ")");
            write(Packets.buildTcp(dstIp, dstPort, srcIp, srcPort,
                    f.ourSeq, f.theirSeq, 0x12, new byte[0]));   // SYN+ACK
            if (dstPort == GameServer.PORT) {
                byte[] sa = Packets.buildTcp(dstIp, dstPort, srcIp, srcPort,
                        f.ourSeq, f.theirSeq, 0x12, new byte[0]);
                StringBuilder hx = new StringBuilder("tcp hex SYNACK(")
                        .append(sa.length).append("B) seq=0x")
                        .append(Integer.toHexString(f.ourSeq))
                        .append(" ack=0x").append(Long.toHexString(f.theirSeq)).append(":");
                for (int i = 0; i < sa.length; i++) {
                    hx.append(String.format("%02x", sa[i] & 0xff));
                }
                Status.log(hx.toString());
            }
            f.ourSeq += 1;
            // Deliberately NOT pumping yet -- see Flow.handshakeDone.  The
            // client's ACK is what starts the server->client direction.
            return;
        }

        if (f == null) {
            // Unknown flow (e.g. our own ACK after teardown): politely reset.
            if (!rst) {
                write(Packets.buildTcp(dstIp, dstPort, srcIp, srcPort, (int) ack,
                        seq + payloadLen, 0x14, new byte[0]));
            }
            return;
        }

        if (!f.handshakeDone && !syn) {
            // The client's handshake ACK has arrived, so the connection is fully
            // established from its point of view and it is safe to relay
            // whatever the local server has already sent.
            f.handshakeDone = true;
            f.pump(this);
        }

        if (rst) {
            Status.log("tcp " + key + " RST from client");
            f.close();
            flows.remove(key);
            FLOWS.set(flows.size());
            return;
        }

        if (payloadLen > 0) {
            f.theirSeq = seq + payloadLen;
            synchronized (f.outLock) {
                f.socket.getOutputStream().write(payload);
                f.socket.getOutputStream().flush();
            }
            if (psh || payloadLen > 0) {
                Status.log("tcp " + key + " <- " + payloadLen + "B");
            }
            // ACK what we received
            write(Packets.buildTcp(dstIp, dstPort, srcIp, srcPort,
                    f.ourSeq, f.theirSeq, 0x10, new byte[0]));
        }

        if (fin) {
            f.theirSeq = seq + payloadLen + 1;
            Status.log("tcp " + key + " FIN from client");
            write(Packets.buildTcp(dstIp, dstPort, srcIp, srcPort, f.ourSeq, f.theirSeq, 0x11, new byte[0]));
            f.ourSeq += 1;
            f.close();
            flows.remove(key);
            FLOWS.set(flows.size());
        }
    }

    /** Sentinel: relay this flow to its real destination instead of a local server. */
    private static final int PASSTHROUGH = -2;

    /**
     * Map a captured destination onto either a server running inside this app
     * or a straight pass-through to the real host.
     *
     *   8500 / 8501 -> the CM game protocol (the dead retail game server)
     *   443 to PATCH_IP -> the HTTPS patch endpoint
     *   443 to anywhere else -> pass-through
     *
     * The last case matters: catch-all routing puts *every* host in the tunnel,
     * and GamePot's login API is plain HTTPS to gamepot.apigw.ntruss.com.  When
     * that was terminated locally the client received our self-signed patch
     * certificate and refused it --
     *
     *     CertificateException: Trust anchor for certification path not found
     *     GAMEPOT: login - GUEST -> initialize fail! {"code":4002}
     *
     * so the guest login popup just reported an error.  Passing those flows
     * through keeps the real service working while the patch host, which no
     * longer exists, stays intercepted.
     *
     * Everything else gets an immediate RST rather than a black hole, so the
     * client's retry loops fail fast instead of piling up 8-second stalls.
     */
    private static int localPortFor(String dstIp, int dstPort) {
        if (dstPort == GameServer.PORT) return GameServer.PORT;
        if (dstPort == 8501) return GameServer.PORT;
        if (dstPort == 443) {
            return ProxyVpnService.PATCH_IP.equals(dstIp) ? PatchServer.PORT : PASSTHROUGH;
        }
        return -1;
    }

    // ---- writing ------------------------------------------------------
    private final Object writeLock = new Object();

    void write(byte[] pkt) {
        synchronized (writeLock) {
            try {
                out.write(pkt);
                out.flush();
                PKT_OUT.incrementAndGet();
            } catch (Exception e) {
                Status.log("tun write: " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    /** One TCP conversation, with a reader thread pumping server -> client. */
    static final class Flow {
        static final AtomicInteger IDS = new AtomicInteger();
        final String key;
        final Socket socket;
        final String dstIp, srcIp;
        final int dstPort, srcPort;
        final int id = IDS.incrementAndGet();
        final Object outLock = new Object();
        volatile int ourSeq;
        volatile long theirSeq;
        private volatile boolean closed;
        private Thread pump;

        /**
         * True once the client's handshake ACK has been seen.
         *
         * The relay must NOT forward anything the local server sent before that
         * point.  Doing so delivers payload in the same instant as the SYN+ACK,
         * and the client's socket layer then leaves the connection marked as
         * still-pending: its own log showed
         *
         *     Tick(169) [Net Connected ... HasPendingConnection(1), PendingDataSize(24)]
         *
         * and it never issued the matching `Net Read SC` -- the 24-byte
         * SC_LOGINOPENRES sat in the buffer while the gamestart screen stayed on
         * LOADING.  The retail server had
         *
         *     Tick(169) [Net Connected ... HasPendingConnection(0), PendingDataSize(0)]
         *     Tick(225) [Net Read SC 1]
         *     Tick(242) [Net SC : SC_LOGINOPENRES(13), OK(1)]
         *
         * i.e. its first packet arrived only after the connection was fully up.
         */
        volatile boolean handshakeDone;

        Flow(String key, Socket s, String dstIp, int dstPort, String srcIp, int srcPort) {
            this.key = key;
            this.socket = s;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
            this.srcIp = srcIp;
            this.srcPort = srcPort;
        }

        void pump(final TunRelay relay) {
            synchronized (this) {
                if (pump != null) return;   // start exactly one reader thread
                pump = new Thread(new PumpTask(relay, this), "flow-" + id);
                pump.setDaemon(true);
                pump.start();
            }
        }

        /**
         * Server -> client pump.
         *
         * `static` because non-static inner classes make R8/D8 abort with an
         * internal NPE (build-tools 33 and 34 alike); the Flow is passed in.
         */
        private static final class PumpTask implements Runnable {
            private final TunRelay relay;
            private final Flow flow;
            PumpTask(TunRelay relay, Flow flow) { this.relay = relay; this.flow = flow; }

            @Override public void run() {
                byte[] buf = new byte[16384];
                try {
                    InputStream is = flow.socket.getInputStream();
                    while (!flow.closed) {
                        int n = is.read(buf);
                        if (n <= 0) break;
                        byte[] data = Arrays.copyOf(buf, n);
                        byte[] pkt = Packets.buildTcp(flow.dstIp, flow.dstPort,
                                flow.srcIp, flow.srcPort,
                                flow.ourSeq, flow.theirSeq, 0x18, data);  // PSH+ACK
                        relay.write(pkt);
                        flow.ourSeq += n;
                        Status.log("tcp " + flow.key + " -> " + n + "B");
                        // Hex dump for the game port only.
                        //
                        // The client's socket shows rx_queue = 0 even though this
                        // write happens, while the SYN+ACK on the same flow and
                        // from the same builder is accepted -- so the difference
                        // must be in the bytes themselves.  Printing them lets us
                        // diff against a known-good packet instead of guessing.
                        if (flow.dstPort == GameServer.PORT) {
                            StringBuilder hx = new StringBuilder("tcp hex(")
                                    .append(pkt.length).append("B):");
                            for (int i = 0; i < pkt.length; i++) {
                                hx.append(String.format("%02x", pkt[i] & 0xff));
                            }
                            Status.log(hx.toString());
                        }
                    }
                } catch (Exception e) {
                    // normal on teardown
                }
                if (!flow.closed) {
                    try {
                        byte[] fin = Packets.buildTcp(flow.dstIp, flow.dstPort,
                                flow.srcIp, flow.srcPort,
                                flow.ourSeq, flow.theirSeq, 0x11, new byte[0]);
                        relay.write(fin);
                        flow.ourSeq += 1;
                    } catch (Exception ignored) { }
                }
            }
        }

        void close() {
            closed = true;
            try { socket.close(); } catch (Exception ignored) { }
        }
    }
}
