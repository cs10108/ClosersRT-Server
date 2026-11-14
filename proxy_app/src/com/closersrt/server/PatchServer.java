package com.closersrt.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/**
 * The HTTPS endpoint the retail client expects for its patch/update step.
 *
 * The client fetches
 *
 *     https://download.closersrt.com/Patch_Live/service-live.txt
 *     https://download.closersrt.com/Patch_Live/PatchInfo/data_service-Android.txt
 *
 * and `service-live.txt` is what carries the GAME server address, so this is the
 * document that lets us point the client back at ourselves.
 *
 * TLS is terminated with the self-signed identity built by
 * tools/make_patch_cert.py (assets/patch.p12).  Most requests in practice come
 * from third-party SDKs (Facebook, Firebase, GamePot) that we simply answer 404
 * so they fail fast instead of hanging; only the Patch_Live paths matter.
 *
 * Everything is a named static class: R8/D8 aborts on non-static inner classes.
 */
public final class PatchServer {

    public static final int PORT = 8443;
    private static final String CRT = "patch.crt";
    private static final String KEY = "patch.key";
    private static final char[] PASSWORD = "closersrt".toCharArray();

    /**
     * Kept so the patch tree can serve files too large to ship inside the APK.
     *
     * `data_service-Android.txt` is the client's table manifest.  The retail
     * copy is ~35 MB -- the same size as the client's own cached
     * TablePack/TableFiles.dat -- and what we return here decides whether the
     * client believes it already has its tables.  It therefore lives in this
     * app's own external files directory:
     *
     *     /sdcard/Android/data/com.closersrt.server/files/data_service-Android.txt
     *
     * which needs no storage permission on any Android version.
     */
    private static android.content.Context appCtx;

    private static ServerSocket server;
    private static volatile boolean running;
    private static volatile String lastRequest = "-";
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private PatchServer() { }

    public static boolean isRunning() { return running; }
    public static String lastRequest() { return lastRequest; }
    public static String hostPort() { return "127.0.0.1:" + PORT; }

    /**
     * Build the TLS context from the PEM pair shipped in assets.
     *
     * We load `patch.crt` + `patch.key` by hand instead of using a PKCS#12
     * keystore: Android 12's BouncyCastle refuses the PBES2/HMAC-SHA256
     * encoding Python emits ("No installed provider supports this key:
     * ...PKCS12Key"), while X.509 and PKCS#8 PEM parse through the standard
     * CertificateFactory/KeyFactory on every Android release.
     */
    private static SSLContext buildContext(android.content.Context ctx) throws Exception {
        java.security.cert.Certificate cert;
        java.io.InputStream cin = ctx.getAssets().open(CRT);
        try {
            cert = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(cin);
        } finally {
            cin.close();
        }

        byte[] keyDer;
        java.io.InputStream kin = ctx.getAssets().open(KEY);
        try {
            keyDer = pemToDer(kin, "PRIVATE KEY");
        } finally {
            kin.close();
        }
        java.security.PrivateKey key = java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyDer));

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("patch", key, PASSWORD,
                new java.security.cert.Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl;
    }

    /** Extract the DER body of a PEM block. */
    private static byte[] pemToDer(java.io.InputStream in, String label)
            throws IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, "US-ASCII"));
        StringBuilder b64 = new StringBuilder();
        boolean inside = false;
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("-----BEGIN")) { inside = true; continue; }
            if (line.startsWith("-----END")) break;
            if (inside) b64.append(line);
        }
        return android.util.Base64.decode(b64.toString(), android.util.Base64.DEFAULT);
    }

    private static volatile byte[] DATA_SERVICE = null;

    /**
     * Read data_service-Android.txt once, at start, off the request path.
     *
     * Measured on a real phone: the first asset read inside a request handler
     * took ~78 s, which is longer than the client's libcurl timeout, so the
     * request "succeeded" only after the game had already failed the step.
     */
    private static void preloadDataService(android.content.Context ctx) {
        if (DATA_SERVICE != null) return;
        Thread t = new Thread(new DataServiceLoader(ctx.getApplicationContext()),
                "data-service-preload");
        t.setDaemon(true);
        t.start();
    }

    private static final class DataServiceLoader implements Runnable {
        private final android.content.Context ctx;
        DataServiceLoader(android.content.Context c) { this.ctx = c; }
        @Override public void run() {
            try {
                byte[] b = readAsset("data_service-Android.txt");
                if (b != null) {
                    DATA_SERVICE = b;
                    Status.log("data_service preloaded (" + b.length + "B)");
                }
            } catch (Throwable t) {
                Status.log("data_service preload failed: " + t);
            }
        }
    }

    public static synchronized void start(android.content.Context ctx) {
        if (running) return;
        appCtx = ctx.getApplicationContext();
        preloadDataService(ctx);
        try {
            SSLContext ssl = buildContext(ctx);
            SSLServerSocket ss = (SSLServerSocket) ssl.getServerSocketFactory()
                    .createServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"));
            ss.setNeedClientAuth(false);
            // Do NOT hardcode TLSv1.3: Android 9 (API 28) predates it and
            // SSLServerSocket.setEnabledProtocols throws
            // "protocol TLSv1.3 is not supported".  Intersect with what this
            // device actually offers.
            try {
                java.util.List<String> want = new java.util.ArrayList<String>();
                java.util.List<String> have = java.util.Arrays.asList(
                        ss.getSupportedProtocols());
                for (String p : new String[]{"TLSv1.2", "TLSv1.3"}) {
                    if (have.contains(p)) want.add(p);
                }
                if (!want.isEmpty()) {
                    ss.setEnabledProtocols(want.toArray(new String[0]));
                }
            } catch (Throwable t) {
                Status.log("protocol selection skipped: " + t);
            }
            try {
                // SNIMatchers live on SSLParameters, not on the socket itself.
                java.util.List<javax.net.ssl.SNIMatcher> matchers =
                        new java.util.ArrayList<javax.net.ssl.SNIMatcher>();
                matchers.add(new SniCapture());
                javax.net.ssl.SSLParameters params = ss.getSSLParameters();
                params.setSNIMatchers(matchers);
                ss.setSSLParameters(params);
            } catch (Throwable t) {
                Status.log("SNI capture unavailable: " + t);
            }
            server = ss;
            running = true;
            Thread t = new Thread(new Acceptor(), "patch-accept");
            t.setDaemon(true);
            t.start();
            Status.log("patch HTTPS listening on " + hostPort());
        } catch (Exception e) {
            Status.log("patch server failed to start: " + e);
            running = false;
        }
    }

    public static synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        server = null;
    }

    private static final class Acceptor implements Runnable {
        @Override public void run() {
            while (running) {
                try {
                    final Socket s = server.accept();
                    POOL.execute(new Handler(s));
                } catch (Exception e) {
                    if (running) Status.log("patch accept: " + e);
                    break;
                }
            }
        }
    }

    private static final class Handler implements Runnable {
        private final Socket socket;
        Handler(Socket s) { this.socket = s; }
        @Override public void run() { serve(socket); }
    }

    // ------------------------------------------------------------------
    private static void serve(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            // drain headers, keeping Range (large paks are fetched in ranges)
            String range = null;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Range")) {
                    range = h.substring(c + 1).trim();
                }
                if (path.endsWith(".pak")) {
                    Status.log("patch hdr " + h);
                }
            }

            lastRequest = method + " " + path;
            Status.log("patch <- " + method + " " + path
                    + (lastSni == null ? "" : "  [sni " + lastSni + "]"));

            // Game-side CM.log upload.  It goes over this TLS port because the
            // game's manifest has no usesCleartextTraffic: Android blocks the
            // plain-HTTP 8080 endpoint from inside the game process.
            if (path.startsWith("/api/gamelog")) {
                if (method.equals("POST")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bo.write(buf, 0, n);
                    }
                    GameLogStore.append(bo.toString("UTF-8"));
                    byte[] ok = ("{\"ok\":true,\"lines\":"
                            + GameLogStore.lineCount() + "}").getBytes("UTF-8");
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + ok.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                    out.write(ok);
                    out.flush();
                    s.close();
                    return;
                }
                byte[] js = GameLogStore.json(4000).getBytes("UTF-8");
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + js.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(js);
                out.flush();
                s.close();
                return;
            }

            if (path.endsWith(".pak") && streamZipPak(out, basename(path), range)) {
                // Let the TLS layer drain before closing; closing immediately
                // truncates large bodies on some Android TLS stacks.
                try { out.flush(); } catch (Throwable ignored) { }
                try { Thread.sleep(4000); } catch (InterruptedException ignored) { }
                s.close();
                return;
            }

            byte[] body = contentFor(path);
            int code = body == null ? 404 : 200;
            if (body == null) body = "not found".getBytes("UTF-8");

            String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                    + "Content-Type: " + contentType(path) + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(body);
            out.flush();
            Status.log("patch -> " + code + " " + path + " (" + body.length + "B)");
            s.close();
        } catch (Exception e) {
            // TLS handshake failures from SDKs that refuse our self-signed cert
            // are expected and noisy; record the SNI so the log stays useful.
            Status.log("patch handshake rejected"
                    + (lastSni == null ? "" : "  [sni " + lastSni + "]")
                    + ": " + e.getClass().getSimpleName());
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static volatile String lastSni = null;

    /**
     * Capture the SNI from each ClientHello.
     *
     * This has to be an SNIMatcher rather than a call to getHandshakeSession():
     * that method only carries the server names once the handshake has started,
     * and we need the hostname precisely when the handshake FAILS, because the
     * client rejecting our certificate is the case we are diagnosing.
     */
    private static final class SniCapture extends javax.net.ssl.SNIMatcher {
        SniCapture() { super(javax.net.ssl.StandardConstants.SNI_HOST_NAME); }
        @Override public boolean matches(javax.net.ssl.SNIServerName name) {
            lastSni = name.toString();
            return true;                 // accept any hostname
        }
    }

    /**
     * Read a staging file from this app's own external files directory.
     *
     * Returns null when the file is absent or unreadable, so callers can fall
     * back to a built-in default.  Reading this directory needs no storage
     * permission: it belongs to the app.
     */
    private static byte[] readExternal(String name) {
        if (appCtx == null) return null;
        java.io.FileInputStream in = null;
        try {
            java.io.File dir = appCtx.getExternalFilesDir(null);
            if (dir == null) return null;
            java.io.File f = new java.io.File(dir, name);
            if (!f.isFile()) return null;
            long len = f.length();
            if (len <= 0 || len > 128L * 1024 * 1024) return null;
            byte[] buf = new byte[(int) len];
            in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            Status.log("patch <- external " + name + " (" + off + "B)");
            return buf;
        } catch (Throwable t) {
            Status.log("external " + name + " failed: " + t);
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Read a built-in asset from the APK (e.g. the pak manifest).
     *
     * WHY THIS EXISTS: the manifest used to live only in the app's external
     * directory.  A fresh install on a normal, unrooted phone cannot put it
     * there, so the client mounted only pakchunk0 and the lobby never loaded.
     * Bundling it as an asset makes a plain install work, while an external
     * copy still wins so the behaviour stays overridable.
     */
    private static byte[] readAsset(String name) {
        if (appCtx == null) return null;
        java.io.InputStream in = null;
        try {
            in = appCtx.getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 128L * 1024 * 1024) return null;
                out.write(buf, 0, n);
            }
            byte[] b = out.toByteArray();
            Status.log("patch <- builtin " + name + " (" + b.length + "B)");
            return b.length > 0 ? b : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
        }
    }

    // ---- pak content (option C) ----------------------------------------

    /**
     * Where the operator keeps the pak files.
     *
     * Empty means "work it out automatically" and the search order is:
     *
     *   1. <app external files>/PakCache          (easy to fill from a PC)
     *   2. /sdcard/Android/data/<game>/files/PakCache   (already unpacked)
     *   3. <app external files>/paks
     *
     * The operator can override it from the web console with
     *   POST /api/paks?dir=/sdcard/Download/closers_paks
     * so the 3.85 GB never has to live in a protected directory.
     */
    static volatile String pakDir = "";

    private static java.util.List<java.io.File> pakRoots() {
        java.util.List<java.io.File> out = new java.util.ArrayList<java.io.File>();
        if (pakDir != null && pakDir.length() > 0) {
            out.add(new java.io.File(pakDir));
            return out;
        }
        java.io.File ext = appCtx == null ? null : appCtx.getExternalFilesDir(null);
        if (ext != null) {
            out.add(new java.io.File(ext, "PakCache"));
            out.add(new java.io.File(ext, "paks"));
        }
        // The game's own unpacked cache: works when the content was installed
        // the old way, and costs nothing to check.
        java.io.File game = new java.io.File(
                "/sdcard/Android/data/com.naddicgames.projectm/files/PakCache");
        out.add(game);
        return out;
    }

    /**
     * Read one pak out of the configured directory.
     *
     * Paks are up to 300 MB, so they are memory-mapped rather than read into
     * a heap array: mapping keeps the copy out of the Java heap (which is
     * small) and lets the OS page them in on demand.  Returns null when the
     * file is absent, which the caller turns into a 404 so the client retries
     * instead of caching an empty file.
     */
    /** How many .pak files the configured directory currently offers. */
    static int countPaks() {
        int n = 0;
        for (java.io.File root : pakRoots()) {
            if (root == null) continue;
            java.io.File[] fs = root.listFiles();
            if (fs == null) continue;
            for (java.io.File f : fs) {
                String nm = f.getName();
                if (nm.endsWith(".pak") && f.isFile()) n++;
            }
            if (n > 0) return n;   // first directory that has any wins
        }
        return n;
    }

    /** First .pak filename found, or null.  Used by the web console. */
    static String firstPakName() {
        for (java.io.File root : pakRoots()) {
            if (root == null) continue;
            java.io.File[] fs = root.listFiles();
            if (fs == null) continue;
            for (java.io.File f : fs) {
                String nm = f.getName();
                if (nm.endsWith(".pak") && f.isFile()) return nm;
            }
        }
        return null;
    }

    /** Last path segment, without pulling in java.io.File for one call. */
    private static String basename(String path) {
        if (path == null) return "";
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /**
     * Serve one pak straight out of the operator-selected content ZIP.
     * Returns true when the entry existed and the response was written.
     */
    private static boolean streamZipPak(OutputStream out, String name, String range) {
        try {
            ContentZip z = ContentZip.get();
            if (z == null) return false;
            ContentZip.Entry e = z.find(name);
            if (e == null) return false;
            long total = e.usize;
            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String r = range.substring(6).trim();
                int dash = r.indexOf('-');
                if (dash >= 0) {
                    try {
                        String a = r.substring(0, dash).trim();
                        String b = r.substring(dash + 1).trim();
                        if (a.length() > 0) start = Long.parseLong(a);
                        if (b.length() > 0) end = Long.parseLong(b);
                        if (start < 0) start = 0;
                        if (end >= total) end = total - 1;
                        if (start <= end) partial = true;
                    } catch (NumberFormatException ignored) { }
                }
            }
            long len = partial ? (end - start + 1) : total;
            InputStream in = z.openRange(e, start, len);
            String head = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + (partial
                        ? ("Content-Length: " + len + "\r\n"
                           + "Content-Range: bytes " + start + "-" + end + "/" + total + "\r\n")
                        : "Transfer-Encoding: chunked\r\n")
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            byte[] buf = new byte[256 * 1024];
            long sent = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (!partial) {
                    out.write((Integer.toHexString(r) + "\r\n").getBytes("UTF-8"));
                }
                out.write(buf, 0, r);
                if (!partial) {
                    out.write("\r\n".getBytes("UTF-8"));
                }
                sent += r;
            }
            if (!partial) {
                out.write("0\r\n\r\n".getBytes("UTF-8"));
            }
            in.close();
            out.flush();
            Status.log("patch <- zip pak " + name + " (" + sent + "B"
                    + (partial ? (", range " + start + "-" + end) : "") + ")");
            return true;
        } catch (Throwable t) {
            Status.log("patch: zip pak failed " + name + ": " + t);
            return false;
        }
    }

    private static byte[] readPak(String name) {
        if (name == null || name.indexOf("..") >= 0 || name.indexOf('/') >= 0) {
            return null;   // never let a request escape the directory
        }
        for (java.io.File root : pakRoots()) {
            if (root == null) continue;
            java.io.File f = new java.io.File(root, name);
            if (!f.isFile()) continue;
            long len = f.length();
            if (len <= 0 || len > 512L * 1024 * 1024) continue;
            try {
                byte[] b = new byte[(int) len];
                java.io.DataInputStream in = new java.io.DataInputStream(
                        new java.io.BufferedInputStream(
                                new java.io.FileInputStream(f), 256 * 1024));
                try {
                    in.readFully(b);
                } finally {
                    in.close();
                }
                Status.log("patch <- pak " + name + " (" + root + ", " + len + "B)");
                return b;
            } catch (Throwable t) {
                Status.log("patch: pak read failed " + name + ": " + t);
            }
        }
        return null;
    }

    /**
     * The patch tree.  `service-live.txt` is the important one: it tells the
     * client where the game server lives.  We keep the RETAIL address so the
     * traffic looks ordinary -- TunRelay captures port 8500 regardless of the
     * destination, so it lands on our in-process GameServer anyway.
     */
    static byte[] contentFor(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);

        if (p.endsWith("/service-live.txt")) {
            String profile = ProfileStore.get().activeBody();
            if (profile != null && profile.contains("ServerIP")) {
                return profile.getBytes(java.nio.charset.Charset.forName("UTF-8"));
            }
            // ---- FORMAT (recovered by disassembling the parser) ----------------
            //
            // UCMPatchDownloader::OnDownloadServiceInfoFile_Complete
            // (libUE4.so @0x07cef97c) does:
            //
            //   FString::ParseIntoArrayLines(content, /*cullEmpty*/ true)
            //   FString::ParseIntoArray(line, /*delim*/ 0x5991d06, true)
            //   cmp  parts.Num(), #2
            //   parts[0].Stricmp(<key>)   ; select the field
            //   value = parts[1]          ; <-- the SECOND field is the value
            //
            // and 0x5991d06 is the single character '|'.
            //
            // So a line is exactly two pipe-separated fields: <Key>|<Value>.
            // An earlier three-field attempt ("ServerIP|Android|175,...") made
            // the client report ServerIP(Android), which proved both that the
            // delimiter is right and that the value is taken from field 1.
            //
            // The value MUST be the comma form.
            //
            // The client splits this into five pieces (four octets plus port).
            // An earlier revision emitted BOTH "175,106,101,33,8500" and
            // "175.106.101.33:8500", and because the parser keeps the LAST
            // match the dotted spelling won -- which splits into a single piece
            // and leaves the client with no usable address.  Its own log showed
            // the difference plainly:
            //
            //     retail : ServerIP(175,106,101,33,8500)
            //     ours   : ServerIP(175.106.101.33:8500)
            //
            // and with no valid address the client never calls
            // UNetAgent::TryConnect, so the gamestart screen sits on LOADING
            // forever.  Only the comma form is emitted now.
            String plat = "Android";
            String ip = "127,0,0,1,8500";
            String dataUrl = "https://127.0.0.1:8443/Patch_Live/"
                    + "PatchInfo/data_service-Android.txt";
            String versionUrl = "https://127.0.0.1:8443/Patch_Live/"
                    + "PatchInfo/patch_version-Android.txt";

            StringBuilder sb = new StringBuilder();
            sb.append("ServerIP-").append(plat).append('|').append(ip).append('\n');
            sb.append("ServerIP|").append(ip).append('\n');
            for (String[] slot : new String[][]{
                    {"DataURL", dataUrl}, {"PatchVersionURL", versionUrl}}) {
                String key = slot[0], val = slot[1];
                sb.append(key).append('-').append(plat).append('|').append(val).append('\n');
                sb.append(key).append('|').append(val).append('\n');
            }
            return sb.toString()
                    .getBytes(java.nio.charset.Charset.forName("UTF-8"));
        }
        if (p.endsWith("/data_service-Android.txt")) {
            // Serve from memory.  Reading this from assets on first use was
            // measured at 78 seconds on a real phone (the client had already
            // given up by then), so the bytes are preloaded in start().
            if (DATA_SERVICE != null) return DATA_SERVICE;
            byte[] staged = readExternal("data_service-Android.txt");
            if (staged != null) return staged;
            byte[] builtin = readAsset("data_service-Android.txt");
            if (builtin != null) return builtin;
            return "Version=1\nTablePack=TableFiles.dat\n"
                    .getBytes(java.nio.charset.Charset.forName("UTF-8"));
        }
        // ---- pak chunks (option C: the proxy serves the content itself) ----
        //
        // The client asks for the chunk paks named in data_service-Android.txt:
        //
        //   /Patch_Live/Chunks-Android/pakchunk10001-Android_ASTC.pak
        //
        // Serving them here is what removes the whole "copy 3.85 GB into
        // Android/obb by hand" step: the operator picks a folder once, and the
        // game downloads the content through its own patching UI.  Nothing has
        // to be placed inside Android/obb or Android/data at all.
        if (p.contains("/Chunks") || p.endsWith(".pak")) {
            byte[] pak = readPak(basename(p));
            if (pak != null) return pak;
            Status.log("patch: pak not found -> " + basename(p));
            return null;
        }
        if (p.contains("/Patch_Live/")) {
            // Anything else under the patch tree we do not have.  A zero-length
            // 200 would make the client think it downloaded an empty file, so
            // 404 is the honest answer.
            return null;
        }
        // SDK endpoints (Facebook, Firebase, GamePot, analytics): fail fast.
        return null;
    }

    private static String contentType(String path) {
        if (path.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) break;
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), "UTF-8");
    }
}
