package com.closersrt.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Answer every DNS query with one fixed address.
 *
 * We route 0.0.0.0/0 through the tun, so the game's lookups for
 * `download.closersrt.com` (and anything the platform SDKs try) arrive here.
 * Returning our own address means the subsequent TCP connection lands in
 * {@link TunRelay}, which is what lets us serve the game without touching the
 * original APK at all.
 */
public final class Dns {

    private Dns() { }

    /**
     * @param query raw DNS payload (without the UDP header)
     * @param ip    the A record to return
     * @return the DNS response payload, or null if the query is malformed
     */
    public static byte[] answer(byte[] query, String ip) {
        if (query == null || query.length < 12) return null;
        ByteBuffer q = ByteBuffer.wrap(query).order(ByteOrder.BIG_ENDIAN);

        int id = q.getShort(0) & 0xFFFF;
        int qdCount = q.getShort(4) & 0xFFFF;
        if (qdCount < 1) return null;

        // walk the QNAME
        int off = 12;
        StringBuilder name = new StringBuilder();
        while (off < query.length) {
            int len = query[off] & 0xFF;
            if (len == 0) { off++; break; }
            if ((len & 0xC0) == 0xC0) { off += 2; break; }   // compression (not expected)
            if (off + 1 + len > query.length) return null;
            if (name.length() > 0) name.append('.');
            name.append(new String(query, off + 1, len,
                    java.nio.charset.StandardCharsets.US_ASCII));
            off += 1 + len;
        }
        if (off + 4 > query.length) return null;
        int qtype = ((query[off] & 0xFF) << 8) | (query[off + 1] & 0xFF);
        int qclass = ((query[off + 2] & 0xFF) << 8) | (query[off + 3] & 0xFF);
        int questionEnd = off + 4;

        String host = name.toString();
        boolean wantA = (qtype == 1 || qtype == 255);
        Status.log("DNS " + host + " type=" + qtype);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, id);
        writeShort(out, 0x8180);                 // standard response, RD+RA, no error
        writeShort(out, 1);                      // QDCOUNT
        writeShort(out, wantA ? 1 : 0);          // ANCOUNT
        writeShort(out, 0);                      // NSCOUNT
        writeShort(out, 0);                      // ARCOUNT
        out.write(query, 12, questionEnd - 12);  // echo the question

        if (wantA) {
            writeShort(out, 0xC00C);             // pointer to the question name
            writeShort(out, 1);                  // TYPE A
            writeShort(out, 1);                  // CLASS IN
            writeInt(out, 30);                   // TTL
            writeShort(out, 4);                  // RDLENGTH
            for (String part : ip.split("\\.")) {
                out.write(Integer.parseInt(part) & 0xFF);
            }
        }

        byte[] res = out.toByteArray();
        byte[] payload = new byte[res.length - 0];
        System.arraycopy(res, 0, payload, 0, res.length);
        // TunRelay logs the result together with the packet direction, which is
        // the part that actually matters when debugging resolution failures.
        return payload;
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write(v & 0xFF);
    }
}
