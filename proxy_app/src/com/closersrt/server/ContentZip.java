package com.closersrt.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Random-access reader for the operator's content ZIP.
 *
 * The ZIP is opened through a SAF URI (ACTION_OPEN_DOCUMENT), so the proxy
 * never needs MANAGE_EXTERNAL_STORAGE and never touches Android/data.
 * Entries are indexed from the central directory; a pak is served by seeking
 * to its local data offset and streaming exactly compressedSize bytes.
 *
 * POC fallback: if no URI is stored yet, a tiny "poc.zip" in assets is copied
 * to cacheDir and used instead, so the streaming path can be tested before the
 * file picker is exercised.
 */
public final class ContentZip {

    private static final String PREFS = "closersrt_content";
    private static final String KEY_URI = "zip_uri";
    private static final String POC_ASSET = "poc.zip";
    private static volatile ContentZip INSTANCE;

    public static final class Entry {
        public final String name;
        public final long offset;
        public final long csize;
        public final long usize;
        public final int method;
        Entry(String n, long o, long c, long u, int m) {
            name = n; offset = o; csize = c; usize = u; method = m;
        }
    }

    private final Context ctx;
    private Uri uri;
    private ParcelFileDescriptor pfd;
    private FileInputStream fis;
    private File sourceFile;
    private FileChannel channel;
    private long size;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private String displayName = "";
    private boolean assetFallback;

    private ContentZip(Context c) { ctx = c; }

    public static ContentZip get() { return INSTANCE; }

    public static void init(Context c) {
        ContentZip z = new ContentZip(c.getApplicationContext());
        INSTANCE = z;
        z.load();
    }

    public synchronized int count() { return entries.size(); }
    public synchronized String displayName() { return displayName; }
    public synchronized Uri zipUri() {
        if (uri != null) return uri;
        return sourceFile == null ? null : Uri.fromFile(sourceFile);
    }

    /** True only for a real operator-selected / external ZIP, not the tiny POC asset. */
    public synchronized boolean isHandoff() {
        return zipUri() != null && entries.size() > 1;
    }
    public synchronized boolean has(String name) { return find(name) != null; }

    public synchronized Entry find(String name) {
        if (name == null) return null;
        Entry e = entries.get(name);
        if (e != null) return e;
        e = entries.get("PakCache/" + name);
        if (e != null) return e;
        for (Entry x : entries.values()) {
            if (x.name.endsWith("/" + name)) return x;
        }
        return null;
    }

    public synchronized List<String> pakNames() {
        List<String> out = new ArrayList<String>();
        for (Entry e : entries.values()) {
            if (e.name.endsWith(".pak")) out.add(e.name);
        }
        return out;
    }

    private void load() {
        // Intentionally no default and no persistence: every proxy process
        // starts with "no ZIP selected".  The operator selects once on first
        // install; later game launches use the already-imported PakCache.
        Status.log("contentzip: no zip selected");
    }

    /** Called by the SAF picker after the user chooses a zip. */
    public synchronized boolean setUri(Uri u) {
        if (!openUri(u, true)) return false;
        Status.log("contentzip: selected " + displayName + " (" + entries.size() + " entries)");
        return true;
    }

    private boolean openUri(Uri u, boolean persist) {
        try {
            close();
            ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(u, "r");
            if (p == null) return false;
            FileInputStream f = new FileInputStream(p.getFileDescriptor());
            pfd = p; fis = f; channel = f.getChannel();
            size = p.getStatSize();
            if (size <= 0) size = channel.size();
            uri = u;
            displayName = u.getLastPathSegment() == null ? u.toString() : u.getLastPathSegment();
            index();
            return true;
        } catch (Throwable t) {
            Status.log("contentzip: open failed: " + t);
            close();
            return false;
        }
    }

    private void openFile(File f, String label) throws IOException {
        close();
        fis = new FileInputStream(f);
        sourceFile = f;
        channel = fis.getChannel();
        size = f.length();
        displayName = label;
        index();
    }

    private synchronized void close() {
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        try { if (fis != null) fis.close(); } catch (Throwable ignored) { }
        pfd = null; fis = null; sourceFile = null; channel = null; size = 0;
        assetFallback = false;
        entries.clear();
    }

    private void index() throws IOException {
        entries.clear();
        if (channel == null || size < 22) throw new IOException("zip too small");
        int tailLen = (int) Math.min(size, 65557);
        byte[] tail = readAt(size - tailLen, tailLen);
        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (le32(tail, i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new IOException("no EOCD");
        int count = le16(tail, eocd + 10);
        long cdSize = le32u(tail, eocd + 12);
        long cdOff = le32u(tail, eocd + 16);
        if (cdSize <= 0 || cdSize > 16L * 1024 * 1024) throw new IOException("bad CD size");
        byte[] cd = readAt(cdOff, (int) cdSize);
        int p = 0;
        for (int k = 0; k < count && p + 46 <= cd.length; k++) {
            if (le32(cd, p) != 0x02014b50) break;
            int method = le16(cd, p + 10);
            long csize = le32u(cd, p + 20);
            long usize = le32u(cd, p + 24);
            int nlen = le16(cd, p + 28);
            int elen = le16(cd, p + 30);
            int clen = le16(cd, p + 32);
            long local = le32u(cd, p + 42);
            String name = new String(cd, p + 46, nlen, "UTF-8");
            // ZIP64 extra (0x0001) for the fields that are 0xFFFFFFFF.
            if (usize == 0xFFFFFFFFL || csize == 0xFFFFFFFFL || local == 0xFFFFFFFFL) {
                int e = p + 46 + nlen;
                int end = e + elen;
                while (e + 4 <= end) {
                    int id = le16(cd, e);
                    int len = le16(cd, e + 2);
                    int q = e + 4;
                    if (id == 1) {
                        if (usize == 0xFFFFFFFFL && q + 8 <= end) { usize = le64(cd, q); q += 8; }
                        if (csize == 0xFFFFFFFFL && q + 8 <= end) { csize = le64(cd, q); q += 8; }
                        if (local == 0xFFFFFFFFL && q + 8 <= end) { local = le64(cd, q); }
                        break;
                    }
                    e = q + len;
                }
            }
            entries.put(name, new Entry(name, local, csize, usize, method));
            p += 46 + nlen + elen + clen;
        }
        Status.log("contentzip: " + entries.size() + " entries from " + displayName);
    }

    private long dataOffset(Entry e) throws IOException {
        byte[] h = readAt(e.offset, 30);
        if (le32(h, 0) != 0x04034b50) throw new IOException("bad local header");
        int nlen = le16(h, 26);
        int elen = le16(h, 28);
        return e.offset + 30 + nlen + elen;
    }

    public InputStream open(Entry e) throws IOException {
        InputStream raw = new ChannelInput(channel, dataOffset(e), e.csize);
        if (e.method == 0) return raw;
        if (e.method == 8) return new InflaterInputStream(raw, new Inflater(true), 256 * 1024);
        throw new IOException("unsupported zip method " + e.method);
    }

    /** Stream a byte range of an entry (used for HTTP Range requests). */
    public InputStream openRange(Entry e, long start, long len) throws IOException {
        if (start < 0) start = 0;
        if (start >= e.usize) start = e.usize;
        if (len < 0) len = 0;
        if (start + len > e.usize) len = e.usize - start;
        if (e.method == 0) {
            return new ChannelInput(channel, dataOffset(e) + start, len);
        }
        InputStream full = open(e);
        long skip = start;
        while (skip > 0) {
            long n = full.skip(skip);
            if (n <= 0) { full.close(); throw new IOException("range skip failed"); }
            skip -= n;
        }
        return new Limited(full, len);
    }

    private byte[] readAt(long pos, int len) throws IOException {
        byte[] b = new byte[len];
        ByteBuffer bb = ByteBuffer.wrap(b);
        int off = 0;
        while (off < len) {
            int r = channel.read(bb, pos + off);
            if (r <= 0) throw new IOException("short read");
            off += r;
        }
        return b;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }
    private static long le32u(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8)
                | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }
    private static int le32(byte[] b, int o) {
        return (int) le32u(b, o);
    }
    private static long le64(byte[] b, int o) {
        return le32u(b, o) | (le32u(b, o + 4) << 32);
    }

    private static final class Limited extends InputStream {
        private final InputStream in;
        private long left;
        Limited(InputStream i, long n) { in = i; left = n; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = (int) Math.min(len, left);
            int r = in.read(b, off, n);
            if (r > 0) left -= r;
            return r;
        }
        @Override public void close() throws IOException { in.close(); }
    }

    /** InputStream over a fixed range of a SharedFileChannel (positional reads). */
    private static final class ChannelInput extends InputStream {
        private final FileChannel ch;
        private long pos;
        private long left;
        ChannelInput(FileChannel c, long p, long n) { ch = c; pos = p; left = n; }
        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r < 0 ? -1 : (b[0] & 0xff);
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = (int) Math.min(len, left);
            ByteBuffer bb = ByteBuffer.wrap(b, off, n);
            int r = ch.read(bb, pos);
            if (r <= 0) return -1;
            pos += r; left -= r;
            return r;
        }
        @Override public int available() { return left > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) left; }
    }
}
