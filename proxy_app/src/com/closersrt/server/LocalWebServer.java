package com.closersrt.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web console the operator uses, bound to 127.0.0.1 only.
 *
 * This mirrors the workflow of the usual local-server launchers for shut-down
 * mobile games:
 *
 *   /                     dashboard
 *   /api/status           JSON status
 *   /api/profiles         list imported data packages
 *   /api/import?name=..   POST a data package (JSON or base64)
 *   /api/users            list accounts
 *   /api/user/create      POST -> creates an account
 *   /api/user/activate    POST -> the "load my JSON" step
 *   /api/log              recent traffic log
 *
 * Plain HTTP on the loopback interface, so nothing leaves the device.
 * The HTML labels are written as \\uXXXX escapes so this source file is pure
 * ASCII and cannot be mangled by a compiler that guesses the wrong encoding.
 */
public final class LocalWebServer {

    private static final LocalWebServer INSTANCE = new LocalWebServer();

    public static LocalWebServer get() { return INSTANCE; }

    private ServerSocket server;
    private Thread acceptor;
    private volatile boolean running;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private LocalWebServer() { }

    /**
     * Accept loop body.
     *
     * Every nested class in this project is `static`: R8/D8 (both the 8.2 in
     * build-tools 34 and the 3.3 in build-tools 33) aborts with an internal
     * NullPointerException on javac's output for NON-static inner classes,
     * while static nested classes dex fine.  The outer instance is therefore
     * passed in explicitly.
     */
    private static final class Acceptor implements Runnable {
        private final LocalWebServer srv;
        Acceptor(LocalWebServer srv) { this.srv = srv; }
        @Override public void run() { srv.acceptLoop(); }
    }

    /** One connection handler. */
    private static final class Handler implements Runnable {
        private final LocalWebServer srv;
        private final Socket socket;
        Handler(LocalWebServer srv, Socket s) { this.srv = srv; this.socket = s; }
        @Override public void run() { srv.handle(socket); }
    }

    public synchronized void start(int port) {
        if (running) return;
        try {
            server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
            running = true;
            acceptor = new Thread(new Acceptor(this), "web-accept");
            acceptor.setDaemon(true);
            acceptor.start();
            Status.log("web console on http://127.0.0.1:" + port + "/");
        } catch (IOException e) {
            Status.log("web server failed to start: " + e);
            running = false;
        }
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    public boolean isRunning() { return running; }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(new Handler(this, s));
            } catch (IOException e) {
                if (running) Status.log("accept: " + e);
                break;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) { s.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(c + 1).trim()); }
                    catch (NumberFormatException ignored) { }
                }
            }
            // A language pack is a whole 35 MB TableFiles.dat.  Reading it into
            // a byte[] and then into a String (which the generic path below
            // does) would double the peak footprint for no reason, so the
            // upload is streamed straight to the staging file.
            if (rawPath.startsWith("/api/lang/upload")) {
                String code = uploadPack(in, contentLength, rawPath);
                writeResponse(out, code);
                s.close();
                return;
            }
            if (rawPath.startsWith("/api/lang/importzip")) {
                // A whole language bundle (zh + ko, dat + map) in one zip.
                // Streamed like the pack upload: the 35 MB containers are far
                // too big to buffer as a request String.
                String code2;
                if (contentLength <= 0) {
                    code2 = "{\"ok\":false,\"err\":\"missing Content-Length\"}";
                } else {
                    code2 = Language.importZip(new Limited(in, contentLength));
                }
                writeResponse(out, code2);
                s.close();
                return;
            }

            byte[] body = new byte[0];
            if (contentLength > 0) {
                body = readExactly(in, contentLength);
            }

            String path = rawPath;
            Map<String, String> query = new HashMap<String, String>();
            int q = rawPath.indexOf('?');
            if (q >= 0) {
                path = rawPath.substring(0, q);
                for (String kv : rawPath.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) {
                        query.put(url(kv.substring(0, e)), url(kv.substring(e + 1)));
                    }
                }
            }

            // Save export as a real download: the browser saves a dated .json
            // instead of rendering it, which needs a Content-Disposition
            // header the generic route() path cannot set.
            if (path.startsWith("/api/save/export") && query.containsKey("download")) {
                byte[] dl = SaveStore.get().exportJson().getBytes(StandardCharsets.UTF_8);
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                        .format(new java.util.Date());
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Content-Disposition: attachment; filename=\"closersrt-save-"
                        + stamp + ".json\"\r\n"
                        + "Content-Length: " + dl.length + "\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(dl);
                out.flush();
                s.close();
                return;
            }
            String outBody = route(method, path, query,
                    new String(body, StandardCharsets.UTF_8));
            byte[] bytes = outBody.getBytes(StandardCharsets.UTF_8);
            String ctype = path.startsWith("/api/")
                    ? "application/json; charset=utf-8"
                    : "text/html; charset=utf-8";
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + ctype + "\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            s.close();
        } catch (Exception e) {
            Status.log("web handle: " + e);
            try { s.close(); } catch (IOException ignored) { }
        }
    }


    /**
     * Stream a language pack to its staging file.
     *
     *   POST /api/lang/upload?lang=zh   <body: the complete TableFiles.dat>
     *
     * Returns null when the request was not an upload (so the caller can fall
     * back to the normal JSON path), otherwise the response body.  Content-Length
     * is honoured; a missing length is refused rather than guessed, because a
     * truncated pack would leave the game unable to parse its own tables.
     */
    private String uploadPack(InputStream in, int contentLength, String rawPath) {
        String lang = "";
        String ext = "dat";
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            for (String kv : rawPath.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e <= 0) continue;
                String k = kv.substring(0, e);
                if (k.equals("lang")) lang = kv.substring(e + 1).trim();
                else if (k.equals("ext")) ext = kv.substring(e + 1).trim();
            }
        }
        if (lang.length() == 0) {
            return "{\"ok\":false,\"err\":\"missing lang\"}";
        }
        if (contentLength <= 0) {
            return "{\"ok\":false,\"err\":\"missing Content-Length\"}";
        }
        try {
            long n;
            if ("map".equals(ext)) {
                // The map must travel with the container: a translated entry is
                // a different length and the client splits the file by it.
                n = Language.stageSidecar(lang, ext, new Limited(in, contentLength));
            } else {
                n = Language.stage(lang, new Limited(in, contentLength));
            }
            Status.log("lang: uploaded " + lang + "." + ext + " (" + n + " bytes)");
            // A fresh container must be re-applied by the game on its next
            // start.  The game-side LanguageFix skips the copy when its marker
            // still matches, and the marker is keyed on pack SIZE -- two
            // translated builds can be byte-identical in length because every
            // string is padded to its original width.  Dropping the marker here
            // makes the next launch copy the new pack unconditionally, so an
            // upload always takes effect without touching the game APK.
            if ("dat".equals(ext)) {
                dropGameMarker();
            }
            return "{\"ok\":true,\"lang\":\"" + lang + "\",\"ext\":\"" + ext
                    + "\",\"bytes\":" + n + "}";
        } catch (Throwable t) {
            Status.log("lang: upload failed: " + t);
            return "{\"ok\":false,\"err\":\"" + t.toString().replace("\"", "'") + "\"}";
        }
    }

    /**
     * Drop the game-side LanguageFix marker so the next launch re-copies the
     * container.  The marker lives in the game's external files dir, which this
     * app cannot write; the game only needs it gone, so we do nothing if the
     * path is not accessible.  Best effort by design -- an upload must still
     * succeed when the game has never run.
     */
    private static void dropGameMarker() {
        try {
            String base = android.os.Environment.getExternalStorageDirectory()
                    .getAbsolutePath();
            java.io.File marker = new java.io.File(base
                    + "/Android/data/com.naddicgames.projectm/files/.langfix");
            if (marker.isFile() && marker.delete()) {
                Status.log("lang: dropped game .langfix marker");
            }
        } catch (Throwable t) {
            Status.log("lang: marker drop skipped: " + t);
        }
    }

    /** Send a plain 200 with the given body. */
    private static void writeResponse(OutputStream out, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) { }
    }


    /**
     * Read exactly `limit` bytes from a socket, then report EOF.
     *
     * The upload body is followed by nothing the server wants, but the socket
     * stays open for the response, so an unbounded read would block until the
     * 15 s timeout on every upload.  Bounding the stream makes the pack size
     * the terminator instead.
     */
    private static final class Limited extends java.io.FilterInputStream {
        private long left;
        Limited(InputStream in, long limit) {
            super(in);
            this.left = limit;
        }
        @Override public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                left--;
            }
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int want = (int) Math.min(len, left);
            int n = super.read(b, off, want);
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }

    private String route(String method, String path, Map<String, String> q, String body) {
        // Debug: raw hex of a login packet, for byte-comparing a rebuild with the
// working build (e.g. /api/pkthex?op=325).
if (path.equals("/api/dormpk") && method.equals("POST")) {
    boolean on = !"0".equals(q.get("v").trim());
    GameServer.sendFavorPackets = on;
    GameServer.sendOrganizePackets = on;
    return "{\"ok\":true,\"favor\":" + on + ",\"organize\":" + on + "}";
}
if (path.equals("/api/dormempty") && method.equals("POST")) {
    GameServer.organizeEmptySlots = !"0".equals(q.get("v").trim());
    return "{\"ok\":true,\"organizeEmptySlots\":" + GameServer.organizeEmptySlots + "}";
}
if (path.equals("/api/pkthex")) {
    int op = Integer.parseInt(q.get("op").trim());
    byte[] b = op == 325 ? GameServer.itemPayload() : op == 327 ? GameServer.fundPayload() : new byte[0];
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x));
    return "{\"op\":" + op + ",\"len\":" + b.length + ",\"hex\":\"" + sb + "\"}";
}
if (path.equals("/api/record")) {
            int cid = 0;
            try { if (q.get("char") != null) cid = Integer.parseInt(q.get("char").trim()); } catch (NumberFormatException ignored) { }
            byte[] p = GameServer.characterPayload();
            int n = java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0x0C);
            int off = 16;
            for (int i = 0; i < n && off + 2 <= p.length; i++) {
                int len = (p[off] & 0xFF) | ((p[off + 1] & 0xFF) << 8);
                off += 2;
                if (off + len > p.length) break;
                int id = java.nio.ByteBuffer.wrap(p, off + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                if (cid == 0 || id == cid) {
                    StringBuilder h = new StringBuilder();
                    for (int k = 0; k < len; k++) h.append(String.format("%02x", p[off + k]));
                    int lvl = (p[off + 0x0D] & 0xFF) | ((p[off + 0x0E] & 0xFF) << 8);
                    int ex = java.nio.ByteBuffer.wrap(p, off + 0x10, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                    return "{\"char\":" + id + ",\"level\":" + lvl + ",\"exp\":" + ex
                            + ",\"class\":" + (p[off + 8] & 0xFF) + ",\"hex\":\"" + h + "\"}";
                }
                off += len;
            }
            return "{\"ok\":false,\"err\":\"char not in roster\"}";
        }
        if (path.equals("/api/growth/set")) {
            int cid = 0, lv = -1, ex = -1;
            try { if (q.get("char") != null) cid = Integer.parseInt(q.get("char").trim()); } catch (NumberFormatException ignored) { }
            try { if (q.get("level") != null) lv = Integer.parseInt(q.get("level").trim()); } catch (NumberFormatException ignored) { }
            try { if (q.get("exp") != null) ex = Integer.parseInt(q.get("exp").trim()); } catch (NumberFormatException ignored) { }
            if (cid <= 0) return "{\"ok\":false,\"err\":\"char\"}";
            SaveStore.CharGrowth g = SaveStore.get().growthOf(cid);
            if (lv >= 0) g.level = lv;
            if (ex >= 0) g.exp = ex;
            SaveStore.get().setGrowth(cid, g);
            return "{\"ok\":true,\"char\":" + cid + ",\"level\":" + g.level + ",\"exp\":" + g.exp + "}";
        }
        if (path.equals("/api/growth/reset")) {
            int lv = 1, ex = 0;
            try { if (q.get("level") != null) lv = Integer.parseInt(q.get("level").trim()); } catch (NumberFormatException ignored) { }
            try { if (q.get("exp") != null) ex = Integer.parseInt(q.get("exp").trim()); } catch (NumberFormatException ignored) { }
            int n = SaveStore.get().resetAllGrowth(lv, ex);
            return "{\"ok\":true,\"chars\":" + n + ",\"level\":" + lv + ",\"exp\":" + ex + "}";
        }
        if (path.equals("/api/chipcat")) {
            if (q.get("v") != null) {
                try { GameServer.chipCat = Integer.parseInt(q.get("v").trim()); } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"chipCat\":" + GameServer.chipCat + "}";
        }
        if (path.equals("/api/costumes")) {
            int cid = 0;
            try { if (q.get("char") != null) cid = Integer.parseInt(q.get("char").trim()); } catch (NumberFormatException ignored) { }
            StringBuilder b = new StringBuilder("[");
            java.util.List<Integer> l = CostumeCatalog.forChar(cid);
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(",");
                b.append(l.get(i));
            }
            return b.append("]").toString();
        }
        if (path.equals("/api/costume")) {
            // /api/costume?char=4105&id=100201   (id=0 clears)
            int cid = 0, cos = 0;
            try { if (q.get("char") != null) cid = Integer.parseInt(q.get("char").trim()); } catch (NumberFormatException ignored) { }
            try { if (q.get("id") != null) cos = Integer.parseInt(q.get("id").trim()); } catch (NumberFormatException ignored) { }
            if (cid <= 0) return "{\"ok\":false,\"err\":\"char\"}";
            String r = GameServer.pushCostume(cid, cos);
            return "{\"ok\":true,\"char\":" + cid + ",\"id\":" + cos + ",\"result\":\"" + r + "\"}";
        }
        if (path.equals("/dorm")) return dormPage();
        if (path.equals("/api/charlist")) return charListJson();
        if (path.equals("/api/dorm")) {
            if (method.equals("POST")) {
                StringBuilder ids = new StringBuilder();
                int n = 0;
                GameServer.favorSlotOf.clear();
                for (int i = 0; i < 6; i++) {
                    String v = q.get("s" + i);
                    if (v == null || v.trim().isEmpty() || v.trim().equals("0")) continue;
                    int cid;
                    try { cid = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { continue; }
                    if (cid <= 0) continue;
                    GameServer.favorSlotOf.put(cid, i);
                    if (n > 0) ids.append(",");
                    ids.append(cid);
                    n++;
                }
                GameServer.favorProbeIds = ids.toString();
                GameServer.favorLimit = n;
                GameServer.favorSlotSpan = 1;
                String push = GameServer.pushDormNow();
                Status.log("dorm: slots -> [" + ids + "] push=" + push);
                return "{\"ids\":\"" + GameServer.favorProbeIds + "\",\"limit\":"
                        + GameServer.favorLimit + ",\"push\":\"" + push + "\"}";
            }
            StringBuilder slots = new StringBuilder("[");
            for (int i = 0; i < 6; i++) {
                int cid = 0;
                for (java.util.Map.Entry<Integer, Integer> e : GameServer.favorSlotOf.entrySet()) {
                    if (e.getValue() != null && e.getValue().intValue() == i) cid = e.getKey();
                }
                if (i > 0) slots.append(",");
                slots.append(cid);
            }
            slots.append("]");
            return "{\"ids\":\"" + GameServer.favorProbeIds + "\",\"limit\":"
                    + GameServer.favorLimit + ",\"slots\":" + slots + "}";
        }
        if (path.equals("/api/favorreset") && method.equals("POST")) {
            int v = 100;
            try { if (q.get("exp") != null) v = Integer.parseInt(q.get("exp").trim()); } catch (Exception ignored) { }
            return "{\"ok\":true,\"result\":\"" + GameServer.resetFavorAll(v) + "\"}";
        }
        if (path.equals("/api/shopmap")) {
            if (method.equals("POST")) {
                int shop = Integer.parseInt(q.get("shop").trim());
                int ci = q.get("costitem") != null ? Integer.parseInt(q.get("costitem").trim()) : 102;
                int ca = q.get("cost") != null ? Integer.parseInt(q.get("cost").trim()) : 0;
                int ri = q.get("item") != null ? Integer.parseInt(q.get("item").trim()) : 101;
                int ra = q.get("count") != null ? Integer.parseInt(q.get("count").trim()) : 0;
                GameServer.SHOP_MAP.put(shop, new int[] { ci, ca, ri, ra });
                return "{\"ok\":true,\"shop\":" + shop + ",\"cost\":[" + ci + "," + ca
                        + "],\"reward\":[" + ri + "," + ra + "]}";
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<Integer, int[]> e : GameServer.SHOP_MAP.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":[");
                for (int k = 0; k < e.getValue().length; k++) {
                    if (k > 0) sb.append(",");
                    sb.append(e.getValue()[k]);
                }
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }
        if (path.equals("/api/favor9") && method.equals("POST")) {
            int lv = 9;
            try { if (q.get("lv") != null) lv = Integer.parseInt(q.get("lv").trim()); } catch (Exception ignored) { }
            return "{\"ok\":true,\"result\":\"" + GameServer.setFavorNine(lv) + "\"}";
        }
        if (path.equals("/api/status")) {
            return statusJson();
        }
        if (path.equals("/api/log")) {
            return "{\"log\":" + Status.logJson() + "}";
        }
        // ---- language packs --------------------------------------------
        //   GET  /api/lang               -> {"ok":true,"current":"ko|zh","packs":{...}}
        //   POST /api/lang?set=zh|ko     -> switch the language
        //   POST /api/lang?pack=zh       -> upload a whole TableFiles.dat
        // The upload is the pack BODY (application/octet-stream), which is why
        // it is handled before the JSON body is parsed.
        if (path.equals("/api/lang")) {
            if (method.equals("POST") && q.get("set") != null) {
                boolean ok = Language.set(q.get("set").trim());
                return "{\"ok\":" + ok + ",\"current\":\"" + Language.current() + "\"}";
            }
            return Language.json();
        }
        // Game-side CM.log upload switch (default OFF).
        //   GET  /api/logcfg        -> {"enabled":false,"lines":N,"bytes":B}
        //   POST /api/logcfg?on=1|0 -> toggle
        // SaveFix asks this once at startup.  Off avoids the 2 s poll, the
        // TLS POSTs and the string copies while the player is in a battle.
        if (path.equals("/api/logcfg")) {
            if (method.equals("POST") && q.get("on") != null) {
                GameLogStore.setEnabled(!"0".equals(q.get("on").trim()));
            }
            return "{\"enabled\":" + GameLogStore.isEnabled()
                 + ",\"lines\":" + GameLogStore.lineCount()
                 + ",\"bytes\":" + GameLogStore.bytes() + "}";
        }
        // Game-side CM.log, pushed by SaveFix because the file itself is
        // unreadable from adb / file managers on a retail device.
        if (path.equals("/api/gamelog") && method.equals("POST")) {
            GameLogStore.append(body);
            return "{\"ok\":true,\"lines\":" + GameLogStore.lineCount() + "}";
        }
        if (path.equals("/api/gamelog")) {
            int n = 2000;
            try {
                if (q.get("n") != null) n = Integer.parseInt(q.get("n"));
            } catch (Exception ignored) { }
            return GameLogStore.json(n);
        }
        if (path.equals("/api/gamelog/clear") && method.equals("POST")) {
            GameLogStore.clear();
            return "{\"ok\":true}";
        }
        if (path.equals("/api/profiles")) {
            return "{\"profiles\":" + ProfileStore.get().json() + "}";
        }
        if (path.equals("/api/import") && method.equals("POST")) {
            return ProfileStore.get().importData(body, q.get("name"));
        }
        if (path.equals("/api/users")) {
            return "{\"users\":" + UserManager.get().json() + "}";
        }
        if (path.equals("/api/user/create") && method.equals("POST")) {
            return UserManager.get().create(q.get("name"), body);
        }
        if (path.equals("/api/user/activate") && method.equals("POST")) {
            boolean ok = UserManager.get().activate(q.get("name"));
            return "{\"ok\":" + ok + "}";
        }

        // ---- assets (gold / prism, inventory, character cards) -----------
        //
        // These drive SC_FUNDINFORES(327), SC_ITEMINFORES(325) and
        // SC_CHARACTERINFO2RES(326).  Everything is persisted in
        // SharedPreferences, so the account keeps its assets across proxy
        // restarts and the game gets them again on the next login.
        if (path.equals("/api/assets")) {
            return AssetStore.get().json();
        }

        // ---- player save (progress) -----------------------------------
        //
        // Separate from /api/assets on purpose.  /api/assets is the operator's
        // configured BASELINE (what a fresh account is seeded with); this is
        // the player's own progress on top of it.  Keeping them apart is what
        // lets you reset an account's baseline without wiping someone's play,
        // and vice versa.
        if (path.equals("/api/session")) {
            // The EFFECTIVE state the game is being answered from: SessionState
            // is the merge of baseline + save, and it is what handlers validate
            // against.  Inspecting the raw save instead is misleading whenever a
            // stack still comes from the baseline, which made several
            // "the item did not change" reports turn out to be measurement
            // error rather than a server bug.
            SessionState st = SessionState.get();
            StringBuilder sb = new StringBuilder("{\"items\":{");
            boolean first = true;
            for (AssetStore.Item it : st.items()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(it.id).append("\":").append(it.count);
            }
            sb.append("},\"funds\":{");
            first = true;
            for (AssetStore.Fund f : st.funds()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(f.id).append("\":").append(f.amount);
            }
            sb.append("},\"characters\":").append(st.characters().size())
              .append(",\"story\":{\"chapter\":").append(st.clearedChapter())
              .append(",\"stage\":").append(st.clearedStage()).append("}}");
            return sb.toString();
        }
        if (path.equals("/api/save")) {
            if (method.equals("POST")) {
                String op = q.get("op");
                if ("reset".equals(op)) {
                    SaveStore.get().reset();
                    SessionState.get().reset();
                    return "{\"ok\":true,\"action\":\"reset\"}";
                }
            }
            return SaveStore.get().json();
        }
        if (path.equals("/api/save/export")) {
            return SaveStore.get().exportJson();
        }
        if (path.equals("/api/save/import") && method.equals("POST")) {
            // Body is the JSON produced by /api/save/export.  importJson()
            // validates it and backs up the previous state before writing.
            if (body == null || body.trim().isEmpty()) {
                return "{\"ok\":false,\"err\":\"empty body\"}";
            }
            return SaveStore.get().importJson(body);
        }
        if (path.equals("/api/save/revision")) {
            return "{\"revision\":" + SaveStore.get().revision()
                    + ",\"formatVersion\":" + SaveStore.FORMAT_VERSION
                    + ",\"storedVersion\":" + SaveStore.get().loadedVersion() + "}";
        }
        // ---- facility reverse-engineering harness ----------------------
        //
        // The building list only ships during CS_ACCOUNTSECTION02, so probing
        // one field offset used to cost a full logout/login.  These endpoints
        // let the operator change the record shape and re-push the packet into
        // the live session, which makes offset probing a few seconds per try.
        if (path.equals("/api/facility")) {
            if (method.equals("POST")) {
                String fl = q.get("flags");
                String ids = q.get("ids");
                String lv = q.get("level");
                String off = q.get("setoff");
                if (fl != null) {
                    try { GameServer.facilityFlags = (int) Long.parseLong(fl.trim(), 16); }
                    catch (NumberFormatException ignored) { }
                }
                if (ids != null) {
                    String[] parts = ids.split(",");
                    int[] out = new int[parts.length];
                    int n = 0;
                    for (String one : parts) {
                        try { out[n++] = Integer.parseInt(one.trim()); }
                        catch (NumberFormatException ignored) { }
                    }
                    int[] trimmed = new int[n];
                    System.arraycopy(out, 0, trimmed, 0, n);
                    GameServer.facilityBuildingIds = trimmed;
                }
                if (lv != null) {
                    try { GameServer.facilityLevel = Integer.parseInt(lv.trim()); }
                    catch (NumberFormatException ignored) { }
                }
                String fn = q.get("fname");
                if (fn != null) {
                    try { GameServer.facilityFname = (int) Long.parseLong(fn.trim()); }
                    catch (NumberFormatException ignored) { }
                }
                if (off != null) {
                    // setoff=<hexOffset>:<value>[,<hexOffset>:<value>...]
                    // Multiple pairs let one request probe several fields at once,
                    // which matters because each attempt needs a fresh level load.
                    String[] pairs = off.split(",");
                    int[] flat = new int[pairs.length * 2];
                    int np = 0;
                    for (String one : pairs) {
                        int c = one.indexOf(':');
                        if (c <= 0) continue;
                        try {
                            flat[np++] = (int) Long.parseLong(one.substring(0, c).trim(), 16);
                            flat[np++] = (int) Long.parseLong(one.substring(c + 1).trim(), 16);
                        } catch (NumberFormatException ignored) { }
                    }
                    int[] trimmed = new int[np];
                    System.arraycopy(flat, 0, trimmed, 0, np);
                    GameServer.facilityCanaryPairs = trimmed;
                    if (np == 2) {
                        GameServer.facilityCanaryOffset = trimmed[0];
                        GameServer.facilityCanaryValue = trimmed[1];
                    }
                }
            }
            String gate = q.get("gate");
            if (gate != null) {
                // gate=<hexOffset>:<value>
                int c = gate.indexOf(':');
                if (c > 0) {
                    try {
                        GameServer.facilityGateWireOffset =
                                (int) Long.parseLong(gate.substring(0, c).trim(), 16);
                        GameServer.facilityGateValue =
                                (int) Long.parseLong(gate.substring(c + 1).trim(), 16);
                    } catch (NumberFormatException ignored) { }
                }
            }
            return "{\"flags\":" + GameServer.facilityFlags
                    + ",\"ids\":" + GameServer.facilityBuildingIds.length
                    + ",\"level\":" + GameServer.facilityLevel
                    + ",\"gate\":[\"0x" + Integer.toHexString(GameServer.facilityGateWireOffset)
                    + "\",\"" + GameServer.facilityGateValue + "\"]"
                    + ",\"canary\":[\"" + GameServer.facilityCanaryOffset
                    + "\",\"" + GameServer.facilityCanaryValue + "\"]}";
        }
        if (path.equals("/api/facility/send")) {
            return "{\"result\":\"" + GameServer.pushFacilityNow() + "\"}";
        }
        if (path.equals("/api/facility/complete")) {
            // One SC_FACILITYBUILDINGUPDATERES(701) pass.  This is the packet
            // that moves a building into manager+0x78, i.e. the one that makes
            // the base screen draw "Lv.1" instead of "constructible".
            return "{\"result\":\"" + GameServer.pushFacilityComplete() + "\"}";
        }
        if (path.equals("/api/facility/flags")) {
            // flags=<create>:<complete>, both hex, e.g. 0a:08
            String fv = q.get("flags");
            if (fv != null) {
                int c = fv.indexOf(':');
                if (c > 0) {
                    try {
                        GameServer.facilityCreateFlags = (int) Long.parseLong(
                                fv.substring(0, c).trim(), 16);
                        GameServer.facilityCompleteFlags = (int) Long.parseLong(
                                fv.substring(c + 1).trim(), 16);
                    } catch (NumberFormatException ignored) { }
                }
            }
            return "{\"create\":\"0x"
                    + Integer.toHexString(GameServer.facilityCreateFlags)
                    + "\",\"complete\":\"0x"
                    + Integer.toHexString(GameServer.facilityCompleteFlags) + "\"}";
        }
        if (path.equals("/api/chips")) {
            if (method.equals("POST") && q.get("char") != null) {
                try {
                    int charId = Integer.parseInt(q.get("char").trim());
                    int chip = q.get("chip") == null ? 0
                            : Integer.parseInt(q.get("chip").trim());
                    GameServer.setCharacterChip(charId, chip);
                } catch (NumberFormatException ignored) { }
            }
            StringBuilder cb = new StringBuilder("{\"ok\":true,\"chips\":{");
            boolean cf = true;
            for (java.util.Map.Entry<Integer, Integer> e
                    : GameServer.characterChips().entrySet()) {
                if (!cf) cb.append(',');
                cf = false;
                cb.append('"').append(e.getKey()).append("\":").append(e.getValue());
            }
            return cb.append("}}").toString();
        }
        if (path.equals("/api/asset/fund") && method.equals("POST")) {
            try {
                int id = Integer.parseInt(q.get("id"));
                long amount = Long.parseLong(q.get("amount"));
                AssetStore.get().setFund(id, amount);
                return "{\"ok\":true," + AssetStore.get().json().substring(1);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?id=&amount=\"}";
            }
        }
        if (path.equals("/api/itemcat") && method.equals("POST")) {
            if (q.get("id") != null && q.get("cat") != null) {
                try {
                    GameServer.itemCatOverride.put(
                            Integer.parseInt(q.get("id").trim()),
                            Integer.parseInt(q.get("cat").trim()));
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"size\":" + GameServer.itemCatOverride.size() + "}";
        }
        if (path.equals("/api/itemlist")) {
            // Catalogue for the picker: [[id, category, chinese, korean], ...]
            StringBuilder sb = new StringBuilder("[[");
            String[] rows = ItemCatalog.rows();
            for (int i = 0; i < rows.length; i++) {
                String[] f = rows[i].split("\\|", -1);
                if (i > 0) sb.append(",[");
                // rows are "id|type|category|chinese|korean"
                sb.append(f[0]).append(",\"")
                  .append(Status.jsonEscape(f.length > 2 ? f[2] : "")).append("\",\"")
                  .append(Status.jsonEscape(f.length > 3 ? f[3] : "")).append("\",\"")
                  .append(Status.jsonEscape(f.length > 4 ? f[4] : "")).append("\"]");
            }
            return sb.append(']').toString();
        }
        if (path.equals("/api/acctexp") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.ACCOUNT_EXP = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("off") != null) {
                try {
                    GameServer.ACCOUNT_EXP_OFFSET = Integer.parseInt(q.get("off").trim(), 16);
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"exp\":" + GameServer.ACCOUNT_EXP
                 + ",\"offset\":" + GameServer.ACCOUNT_EXP_OFFSET + "}";
        }
        if (path.equals("/api/asset/item") && method.equals("POST")) {
            try {
                int id = Integer.parseInt(q.get("id"));
                int count = q.get("count") == null ? 1 : Integer.parseInt(q.get("count"));
                AssetStore.get().addItem(id, count);
                return "{\"ok\":true," + AssetStore.get().json().substring(1);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?id=&count=\"}";
            }
        }
        if (path.equals("/api/asset/setitem") && method.equals("POST")) {
            // Absolute set, unlike /api/asset/item which accumulates.  Needed
            // to put a stack at an exact number without doing arithmetic
            // against whatever happened to be there.
            try {
                int id = Integer.parseInt(q.get("id"));
                int count = q.get("count") == null ? 0 : Integer.parseInt(q.get("count"));
                AssetStore.get().setItem(id, Math.max(0, count));
                return "{\"ok\":true," + AssetStore.get().json().substring(1);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?id=&count=\"}";
            }
        }
        if (path.equals("/api/asset/item/remove") && method.equals("POST")) {
            try {
                AssetStore.get().removeItem(Integer.parseInt(q.get("id")));
                return "{\"ok\":true," + AssetStore.get().json().substring(1);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?id=\"}";
            }
        }
        if (path.equals("/api/asset/char") && method.equals("POST")) {
            String id = q.get("id");
            if (id == null || id.trim().isEmpty()) {
                return "{\"ok\":false,\"error\":\"need ?id=\"}";
            }
            AssetStore.get().addCharacter(id.trim());
            return "{\"ok\":true," + AssetStore.get().json().substring(1);
        }
        if (path.equals("/api/asset/char/remove") && method.equals("POST")) {
            String id = q.get("id");
            if (id != null) AssetStore.get().removeCharacter(id.trim());
            return "{\"ok\":true," + AssetStore.get().json().substring(1);
        }
        if (path.equals("/api/asset/fund/remove") && method.equals("POST")) {
            try {
                AssetStore.get().removeFund(Integer.parseInt(q.get("id")));
                return "{\"ok\":true," + AssetStore.get().json().substring(1);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?id=\"}";
            }
        }
        // Wipe everything so the server falls back to the known-good baseline
        // payloads (a bare record count), which is what reaches the lobby most
        // reliably.  Use this before a stability comparison run.
        if (path.equals("/api/asset/story") && method.equals("POST")) {
            try {
                int ch = q.get("chapter") == null ? 0 : Integer.parseInt(q.get("chapter"));
                int st = q.get("stage") == null ? 0 : Integer.parseInt(q.get("stage"));
                AssetStore.get().setStoryProgress(ch, st);
                return "{\"ok\":true," + AssetStore.get().json().substring(1);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?chapter=&stage=\"}";
            }
        }
        // ---- pak content path (option C) ---------------------------------
        //
        // Lets the operator point the proxy at a folder full of chunk paks, so
        // the game downloads them through its own patching UI and nothing has
        // to be copied into Android/obb or Android/data by hand.
        if (path.equals("/api/paks")) {
            StringBuilder sb = new StringBuilder("{\"dir\":\"");
            sb.append(Status.jsonEscape(PatchServer.pakDir)).append("\",");
            sb.append("\"files\":").append(PatchServer.countPaks()).append(",");
            sb.append("\"sample\":\"");
            String first = PatchServer.firstPakName();
            sb.append(Status.jsonEscape(first == null ? "" : first)).append("\"}");
            return sb.toString();
        }
        if (path.equals("/api/paks/set") && method.equals("POST")) {
            PatchServer.pakDir = q.get("dir") == null ? "" : q.get("dir");
            Status.log("pak dir set to: " + PatchServer.pakDir);
            return "{\"ok\":true,\"dir\":\""
                    + Status.jsonEscape(PatchServer.pakDir) + "\",\"files\":"
                    + PatchServer.countPaks() + "}";
        }
        if (path.equals("/api/asset/clear") && method.equals("POST")) {
            AssetStore.get().clearAll();
            return "{\"ok\":true," + AssetStore.get().json().substring(1);
        }
        if (path.equals("/api/asset/reset") && method.equals("POST")) {
            AssetStore.get().reset();
            return "{\"ok\":true," + AssetStore.get().json().substring(1);
        }
        // Tune the pause before the CM opening packet, so the window in which
        // the client accepts SC_LOGINOPENRES can be swept without rebuilding.
        if (path.equals("/api/delay") && method.equals("POST")) {
            try {
                GameServer.openingDelayMs = Math.max(0, Integer.parseInt(q.get("ms")));
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"bad ms\"}";
            }
            return "{\"ok\":true,\"ms\":" + GameServer.openingDelayMs + "}";
        }
        // Diagnostic: send N KB right after the opening packet, to prove whether
        // the relay really delivers bytes to the client's socket.
        if (path.equals("/api/blob") && method.equals("POST")) {
            try {
                GameServer.blobKb = Math.max(0, Integer.parseInt(q.get("kb")));
                if (q.get("burst") != null) {
                    GameServer.burst = Math.max(0, Integer.parseInt(q.get("burst")));
                }
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"bad kb/burst\"}";
            }
            return "{\"ok\":true,\"kb\":" + GameServer.blobKb
                    + ",\"burst\":" + GameServer.burst + "}";
        }
        // Diagnostic: send one frame with a distinct value in every header slot,
        // so the client's own log names the wire offset of each parsed field.
        if (path.equals("/api/fingerprint") && method.equals("POST")) {
            GameServer.fingerprint = !"0".equals(q.get("on"));
            return "{\"ok\":true,\"on\":" + GameServer.fingerprint + "}";
        }
        // Diagnostic: sweep the wire header without rebuilding.
        //   /api/header?size=32&f16=0&f20=0
        if (path.equals("/api/header") && method.equals("POST")) {
            try {
                if (q.get("size") != null) {
                    GameServer.hdrSize = Integer.parseInt(q.get("size"));
                }
                if (q.get("f16") != null) {
                    GameServer.hdrField16 = (int) Long.parseLong(q.get("f16"));
                }
                if (q.get("f20") != null) {
                    GameServer.hdrField20 = (int) Long.parseLong(q.get("f20"));
                }
                if (q.get("op") != null) {
                    GameServer.hdrOpcodeOverride =
                            (int) Long.parseLong(q.get("op"));
                }
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"bad header args\"}";
            }
            return "{\"ok\":true,\"size\":" + GameServer.hdrSize
                    + ",\"f16\":" + GameServer.hdrField16
                    + ",\"f20\":" + GameServer.hdrField20 + "}";
        }
        // Toggle frame crypto per direction: /api/crypto?enc=1&dec=0
        if (path.equals("/api/crypto") && method.equals("POST")) {
            if (q.get("enc") != null) {
                GameServer.encryptFrames = !"0".equals(q.get("enc"));
            }
            if (q.get("dec") != null) {
                GameServer.decryptFrames = !"0".equals(q.get("dec"));
            }
            if (q.get("raw") != null) {
                GameServer.rawLog = !"0".equals(q.get("raw"));
            }
            return "{\"ok\":true,\"enc\":" + GameServer.encryptFrames
                    + ",\"dec\":" + GameServer.decryptFrames
                    + ",\"raw\":" + GameServer.rawLog + "}";
        }
        // Character list for SC_CHARACTERINFO2RES: /api/chars?list=4105,4106
        //
        // NOTE: a non-empty list currently crashes the client in OpenLevel.
        // The 48-byte record layout is only partly known (see characterPayload).
        if (path.equals("/api/chars") && method.equals("POST")) {
            if (q.get("list") != null) {
                GameServer.charList = q.get("list");
            }
            return "{\"ok\":true,\"list\":\"" + GameServer.charList
                    + "\",\"warning\":\"non-empty list crashes the client\"}";
        }
        // Character record flags for the FGB_CharacterInfo probe:
        //   /api/charflags?v=N   (N in hex or decimal)
        if (path.equals("/api/charflags") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    String raw = q.get("v").trim();
                    int v = raw.startsWith("0x") || raw.startsWith("0X")
                            ? Integer.parseInt(raw.substring(2), 16)
                            : Integer.parseInt(raw);
                    GameServer.charRecordFlags = v;
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"charRecordFlags\":" + GameServer.charRecordFlags + "}";
        }
        // Character record extras:
        //   /api/charclass?v=N   class id written to FGB_CharacterInfo +0x08
        //   /api/charlevel?v=N   level  written to FGB_CharacterInfo +0x0C
        if (path.equals("/api/charclass") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.charClassId = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"charClassId\":" + GameServer.charClassId + "}";
        }
        if (path.equals("/api/charlevel") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.charLevel = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"charLevel\":" + GameServer.charLevel + "}";
        }
        // Fund record probe fields:
        //   /api/fundflags?v=N   flags written to FGB_FundInfo +0x00
        //   /api/fundtype?v=N    ETB_Common_Fund written to +0x04
        if (path.equals("/api/fundflags") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.fundRecordFlags = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"fundRecordFlags\":" + GameServer.fundRecordFlags + "}";
        }
        if (path.equals("/api/fundtype") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.fundTypeId = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"fundTypeId\":" + GameServer.fundTypeId + "}";
        }
        if (path.equals("/api/fundenable") && method.equals("POST")) {
            // Opens the record path for a single experiment.  Reset to disabled
            // by restarting the proxy, so a bad shape cannot persist.
            if (q.get("v") != null) {
                boolean on = "1".equals(q.get("v").trim());
                GameServer.FUND_RECORDS_DISABLED = !on;
                GameServer.fundSendRecords = on;
            }
            return "{\"ok\":true,\"disabled\":" + GameServer.FUND_RECORDS_DISABLED + "}";
        }
        if (path.equals("/api/fundsend") && method.equals("POST")) {
            // BUG-001 is fixed: SC_FUNDINFORES carries 14-byte records
            // (u16 len + flags 0x02 + key u16 @+4 + amount 8B @+6).  The old
            // hard-disable came from a probe build and is no longer correct.
            boolean on = q.get("v") == null || "1".equals(q.get("v").trim());
            GameServer.FUND_RECORDS_DISABLED = false;
            GameServer.fundSendRecords = on;
            return "{\"ok\":true,\"fundSendRecords\":" + GameServer.fundSendRecords + "}";
        }
        if (path.equals("/api/charexp") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.charExp = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"charExp\":" + GameServer.charExp + "}";
        }
        if (path.equals("/api/expprobe") && method.equals("POST")) {
            if (q.get("v") != null) {
                GameServer.charExpProbe = !("0".equals(q.get("v").trim())
                        || "false".equalsIgnoreCase(q.get("v").trim()));
            }
            return "{\"ok\":true,\"charExpProbe\":" + GameServer.charExpProbe + "}";
        }
        if (path.equals("/api/charpush") && method.equals("POST")) {
            // Diagnostics only: re-send one character record with explicit
            // flags so the layout can be probed live.
            int id = 0, flags = GameServer.charUpdateFlags;
            try { if (q.get("id") != null) id = Integer.parseInt(q.get("id").trim()); }
            catch (NumberFormatException ignored) { }
            try {
                if (q.get("flags") != null) {
                    String f = q.get("flags").trim();
                    flags = f.startsWith("0x") || f.startsWith("0X")
                            ? Integer.parseInt(f.substring(2), 16)
                            : Integer.parseInt(f);
                }
            } catch (NumberFormatException ignored) { }
            int cls = -1, skill = -1, lv = -1;
            try { if (q.get("cls") != null) cls = Integer.parseInt(q.get("cls").trim()); }
            catch (NumberFormatException ignored) { }
            try { if (q.get("skill") != null) skill = Integer.parseInt(q.get("skill").trim()); }
            catch (NumberFormatException ignored) { }
            try { if (q.get("lv") != null) lv = Integer.parseInt(q.get("lv").trim()); }
            catch (NumberFormatException ignored) { }
            String res = GameServer.pushCharNow(id, flags, cls, skill, lv);
            return "{\"ok\":true,\"result\":\"" + Status.jsonEscape(res) + "\"}";
        }
        if (path.equals("/api/charskills") && method.equals("POST")) {
            if (q.get("v") != null) {
                String[] parts = q.get("v").trim().split(",");
                int[] vals = new int[7];
                for (int i = 0; i < 7; i++) {
                    if (i < parts.length) {
                        try {
                            vals[i] = Integer.parseInt(parts[i].trim());
                        } catch (NumberFormatException ignored) { }
                    }
                }
                GameServer.charSkills = vals;
                // Persist, otherwise a proxy restart silently reverts this to
                // the compiled default and "all skills level 3" stops working.
                GameServer.saveKnobs();
            }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"charSkills\":[");
            for (int i = 0; i < GameServer.charSkills.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(GameServer.charSkills[i]);
            }
            return sb.append("]}").toString();
        }
        if (path.equals("/api/sentinel") && method.equals("POST")) {
            if (q.get("v") != null) {
                GameServer.charSentinels = !("0".equals(q.get("v").trim())
                        || "false".equalsIgnoreCase(q.get("v").trim()));
            }
            return "{\"ok\":true,\"charSentinels\":" + GameServer.charSentinels + "}";
        }
        if (path.equals("/api/charleveloff") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.levelOffset = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"levelOffset\":" + GameServer.levelOffset + "}";
        }
        // /api/clock?mode=N   0=legacy cancel, 1=direct serverTime/utcOffset
        if (path.equals("/api/clock") && method.equals("POST")) {
            if (q.get("mode") != null) {
                try {
                    GameServer.CLOCK_MODE = Integer.parseInt(q.get("mode").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"clockMode\":" + GameServer.CLOCK_MODE + "}";
        }
        // /api/keymode?v=N   0=numeric stage id, 1=array index, 2=string hash
        if (path.equals("/api/keymode") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.STAGE_KEY_MODE = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"keymode\":" + GameServer.STAGE_KEY_MODE + "}";
        }
        // Account progress at 209 struct+0x64 -> manager+0x130 (story unlock
        // type-1 rule).  /api/progress?v=NNNN  (v=-1 restores the 0x04 fill)
        if (path.equals("/api/progress") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.ACCOUNT_PROGRESS = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"progress\":" + GameServer.ACCOUNT_PROGRESS + "}";
        }
        // Stage ids handed to the client in SC_STAGEINFORES(322).
        // /api/stage?n=NNN  (n=-1 for all, 0 to disable story unlocking)
        // Bisection switch for the lobby-load crash:
        //   /api/content?on=0  -> send no content packets (322/324/325/327)
        if (path.equals("/api/content") && method.equals("POST")) {
            if (q.get("on") != null) {
                try {
                    GameServer.contentPackets = (int) Long.parseLong(
                            q.get("on").trim(), 16) & 0x1F;
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"contentPackets\":" + GameServer.contentPackets + "}";
        }
        if (path.equals("/api/session/give") && method.equals("POST")) {
            // Simulates something earned while playing: it only touches the
            // in-memory SessionState, so a restart puts the account back to
            // the configured defaults.
            try {
                int id = Integer.parseInt(q.get("id"));
                int count = q.get("count") == null ? 1 : Integer.parseInt(q.get("count"));
                int now = SessionState.get().addItem(id, count);
                return "{\"ok\":true,\"item\":" + id + ",\"count\":" + now
                     + ",\"note\":\"session only - restart the client to reset\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"need ?id=&count=\"}";
            }
        }
        if (path.equals("/api/session/info")) {
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"items\":[");
            List<AssetStore.Item> its = SessionState.get().items();
            for (int i = 0; i < its.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(its.get(i).id)
                  .append(",\"count\":").append(its.get(i).count).append('}');
            }
            sb.append("],\"funds\":[");
            List<AssetStore.Fund> fs = SessionState.get().funds();
            for (int i = 0; i < fs.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"id\":").append(fs.get(i).id)
                  .append(",\"amount\":").append(fs.get(i).amount).append('}');
            }
            sb.append("],\"chars\":").append(SessionState.get().characters().size());
            sb.append(",\"loginAt\":").append(SessionState.get().loginAt());
            return sb.append('}').toString();
        }
        if (path.equals("/api/favor") && method.equals("POST")) {
            // /api/favor?points=6000&limit=-1&ids=4105,4106&tail=0
            if (q.get("points") != null) {
                try {
                    GameServer.favorPoints = Integer.parseInt(q.get("points").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("limit") != null) {
                try {
                    GameServer.favorLimit = Integer.parseInt(q.get("limit").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("tail") != null) {
                try {
                    GameServer.favorTailBytes = Integer.parseInt(q.get("tail").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("span") != null) {
                try {
                    GameServer.favorSlotSpan = Integer.parseInt(q.get("span").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("slot") != null) {
                try {
                    GameServer.favorSlotBase = Integer.parseInt(q.get("slot").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("flags") != null) {
                try {
                    GameServer.favorRecordFlags = Integer.parseInt(q.get("flags").trim(), 16);
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("fscan") != null) {
                try {
                    GameServer.favorKeyScan = Integer.parseInt(q.get("fscan").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("scan") != null) {
                try {
                    GameServer.fundKeyScan = Integer.parseInt(q.get("scan").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("goldkey") != null) {
                try {
                    GameServer.fundTypeGoldKey = Integer.parseInt(q.get("goldkey").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("mirror") != null) {
                GameServer.favorPointMirror = !"0".equals(q.get("mirror").trim());
            }
            // Character-record affinity (the number the 캐릭터 상세 > 호감도 tab
            // prints).  separate from the facility 334 payload above.
            if (q.get("clvl") != null) {
                try {
                    GameServer.charFavorLevel = Integer.parseInt(q.get("clvl").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("cexp") != null) {
                try {
                    GameServer.charFavorExp = Integer.parseInt(q.get("cexp").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("cfavor") != null) {
                GameServer.charSendFavor = !"0".equals(q.get("cfavor").trim());
            }
            if (q.containsKey("ids")) {
                String v = q.get("ids").trim();
                GameServer.favorProbeIds = v.isEmpty() ? null : v;
            }
            return "{\"ok\":true,\"points\":" + GameServer.favorPoints
                 + ",\"clvl\":" + GameServer.charFavorLevel
                 + ",\"cexp\":" + GameServer.charFavorExp
                 + ",\"limit\":" + GameServer.favorLimit
                 + ",\"tail\":" + GameServer.favorTailBytes
                 + ",\"ids\":\"" + (GameServer.favorProbeIds == null ? "" : GameServer.favorProbeIds) + "\"}";
        }
        // Narrative daily-entry reset (532).  /api/narreset?left=3&max=3
        // The 9-byte FGB_NarrativeInfo is [left, max, 0...]; pinning both to 3
        // keeps the bottom-right counter at 3/3 and never consumes an entry.
        if (path.equals("/api/narreset") && method.equals("POST")) {
            if (q.get("on") != null) {
                GameServer.sendNarrativeReset = !"0".equals(q.get("on").trim());
            }
            if (q.get("hex") != null) {
                GameServer.narrativeResetHex = q.get("hex").trim();
            }
            if (q.get("left") != null) {
                try { GameServer.narrativeDailyLeft = Integer.parseInt(q.get("left").trim()); }
                catch (NumberFormatException ignored) { }
            }
            if (q.get("max") != null) {
                try { GameServer.narrativeDailyMax = Integer.parseInt(q.get("max").trim()); }
                catch (NumberFormatException ignored) { }
            }
            // Push the new counter immediately, without waiting for a re-login.
            if (GameServer.liveOut != null) {
                try {
                    GameServer.send(GameServer.liveOut, 532, GameServer.liveSession,
                                    GameServer.narrativeResetPayload());
                } catch (Exception e) { Status.log("narreset: push failed " + e); }
            }
            return "{\"ok\":true,\"send\":" + GameServer.sendNarrativeReset
                 + ",\"left\":" + GameServer.narrativeDailyLeft
                 + ",\"max\":" + GameServer.narrativeDailyMax
                 + ",\"hex\":\"" + GameServer.narrativeResetHex + "\"}";
        }
        // Mark one side-story stage cleared (3 stars) and push the result.
        //   /api/narrclear?id=8410701
        // This is the same path CS_GameFinish(102) takes once a side-story
        // battle really ends, so it can be used to verify 3-star + unlock
        // without replaying the battle.
        if (path.equals("/api/narrclear") && method.equals("POST")) {
            int id = 0;
            if (q.get("id") != null) {
                try { id = Integer.parseInt(q.get("id").trim()); }
                catch (NumberFormatException ignored) { }
            }
            if (id < 8400000 || id > 8499999 || !GameServer.isNarrativeStageId(id)) {
                return "{\"ok\":false,\"error\":\"not a side-story stage id\"}";
            }
            boolean added = GameServer.markNarrativeCleared(id);
            if (GameServer.liveOut != null) {
                try {
                    GameServer.send(GameServer.liveOut, GameServer.SC_NARRATIVERESULTRES,
                                    GameServer.liveSession,
                                    GameServer.narrativeResultPayload(id));
                } catch (Exception e) { Status.log("narrclear: push failed " + e); }
            }
            return "{\"ok\":true,\"id\":" + id + ",\"added\":" + added
                 + ",\"cleared\":" + GameServer.narrativeCleared().size() + "}";
        }
        // List / clear the live side-story progress.
        if (path.equals("/api/narrlist")) {
            if (q.get("reset") != null && !"0".equals(q.get("reset").trim())) {
                SaveStore.get().resetNarrative();
            }
            StringBuilder sb2 = new StringBuilder("{\"ok\":true,\"cleared\":[");
            boolean f2 = true;
            for (Integer id : GameServer.narrativeCleared()) {
                if (!f2) sb2.append(',');
                f2 = false;
                sb2.append(id);
            }
            sb2.append("],\"dailyLeft\":").append(GameServer.narrativeDailyLeft)
              .append(",\"dailyMax\":").append(GameServer.narrativeDailyMax)
              .append(",\"clearBase\":").append(GameServer.characterClearCount).append("}");
            return sb2.toString();
        }
        if (path.equals("/api/stageprobe") && method.equals("POST")) {
            if (q.get("v") != null) {
                try {
                    GameServer.stageClearProbe = Integer.parseInt(q.get("v").trim());
                } catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"stageClearProbe\":" + GameServer.stageClearProbe + "}";
        }
        if (path.equals("/api/stage") && method.equals("POST")) {
            if (q.get("n") != null) {
                try {
                    GameServer.stageLimit = Integer.parseInt(q.get("n").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("nar") != null) {
                GameServer.narrativeStages = !"0".equals(q.get("nar").trim());
            }
            if (q.get("ex") != null) {
                GameServer.exStages = !"0".equals(q.get("ex").trim());
            }
            if (q.get("char") != null) {
                GameServer.characterStages = !"0".equals(q.get("char").trim());
            }
            if (q.get("charlimit") != null) {
                try {
                    GameServer.characterStageLimit =
                            Integer.parseInt(q.get("charlimit").trim());
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("staronly") != null) {
                GameServer.characterStarOnly = !"0".equals(q.get("staronly").trim());
            }
            if (q.get("zero") != null) {
                GameServer.characterZero = !"0".equals(q.get("zero").trim());
            }
            if (q.get("clear") != null) {
                try {
                    GameServer.characterClearCount = Integer.parseInt(q.get("clear").trim());
                    // Changing the baseline discards live clears; otherwise an
                    // old clear would keep a stage locked after lowering N.
                    SaveStore.get().resetNarrative();
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("remain") != null) {
                try {
                    int v = Integer.parseInt(q.get("remain").trim());
                    GameServer.characterEntryRemain = v;
                    GameServer.narrativeDailyLeft = v;
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("max") != null) {
                try {
                    int v = Integer.parseInt(q.get("max").trim());
                    GameServer.characterEntryMax = v;
                    GameServer.narrativeDailyMax = v;
                } catch (NumberFormatException ignored) { }
            }
            if (q.get("stamp") != null) {
                try { GameServer.characterStamp = Long.parseLong(q.get("stamp").trim()); }
                catch (NumberFormatException ignored) { }
            }
            if (q.get("play") != null) {
                try { GameServer.characterPlayCount = Integer.parseInt(q.get("play").trim()); }
                catch (NumberFormatException ignored) { }
            }
            if (q.get("clearused") != null) {
                try { GameServer.characterClearUsed = Integer.parseInt(q.get("clearused").trim()); }
                catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"stageLimit\":" + GameServer.stageLimit
                 + ",\"narrative\":" + GameServer.narrativeStages
                 + ",\"ex\":" + GameServer.exStages
                 + ",\"char\":" + GameServer.characterStages
                 + ",\"charlimit\":" + GameServer.characterStageLimit
                 + ",\"staronly\":" + GameServer.characterStarOnly
                 + ",\"zero\":" + GameServer.characterZero
                 + ",\"clear\":" + GameServer.characterClearCount
                 + ",\"remain\":" + GameServer.characterEntryRemain
                 + ",\"max\":" + GameServer.characterEntryMax
                 + ",\"stamp\":" + GameServer.characterStamp
                 + ",\"play\":" + GameServer.characterPlayCount
                 + ",\"clearused\":" + GameServer.characterClearUsed + "}";
        }
        // Gap between SC_ACCOUNTSECTION06RES and SC_ACCOUNTINFOFINISHRES:
        // Server-clock calibration: /api/stamp?v=NNN
        //
        // Sets the marker written into SC_ACCOUNTINFORES at struct+0xA0 so the
        // year the client reports can be mapped back to a tick count.
        // v=0 restores the real clock.
        if (path.equals("/api/stamp") && method.equals("POST")) {
            if (q.get("v") != null) {
                GameServer.STAMP_PROBE = Long.parseLong(q.get("v"));
            }
            return "{\"ok\":true,\"probe\":" + GameServer.STAMP_PROBE + "}";
        }
        // /api/gap?ms=160
        if (path.equals("/api/gap") && method.equals("POST")) {
            if (q.get("ms") != null) {
                GameServer.SECTION_GAP_MS = Integer.parseInt(q.get("ms"));
            }
            return "{\"ok\":true,\"ms\":" + GameServer.SECTION_GAP_MS + "}";
        }
        // Tutorial progress record: /api/misc?on=0|1
        //
        // SC_ACCOUNTMISCINFORES(223) carries FGB_AccountMiscInfo, whose MainScene
        // byte is the tutorial progress the lobby checks before it will unlock
        // the normal UI.  Without it the client stays in the new-player flow.
        if (path.equals("/api/miscfill") && method.equals("POST")) {
            if (q.get("v") != null) {
                try { GameServer.accountMiscFill = Integer.parseInt(q.get("v").trim(), 16); }
                catch (NumberFormatException ignored) { }
            }
            return "{\"ok\":true,\"miscFill\":" + GameServer.accountMiscFill + "}";
        }
        if (path.equals("/api/misc") && method.equals("POST")) {
            if (q.get("on") != null) {
                GameServer.sendAccountMisc =
                        !("0".equals(q.get("on")) || "false".equals(q.get("on")));
            }
            return "{\"ok\":true,\"send\":" + GameServer.sendAccountMisc + "}";
        }
        // Skip the newbie tutorial: /api/tutorial?skip=0|1
        //
        // Distinct from /api/misc, which decides whether the misc record is
        // sent at all.  This one decides what it SAYS: with skip=1 the account
        // reports MainScene = 7 ("tutorial finished") and the lobby unlocks
        // straight away; with skip=0 it keeps the default 0x04 fill and the
        // tutorial plays from the beginning.
        //
        // Takes effect on the next login (the client only reads this record
        // inside SC_ACCOUNTMISCINFORES during account setup), so restart the
        // game after changing it.
        if (path.equals("/api/tutorial") && method.equals("POST")) {
            if (q.get("skip") != null) {
                String v = q.get("skip").trim();
                GameServer.skipTutorial =
                        !("0".equals(v) || "false".equalsIgnoreCase(v));
            }
            return "{\"ok\":true,\"skipTutorial\":" + GameServer.skipTutorial + "}";
        }
        // Fixed default save: /api/save            -> status
        //                    /api/save?auto=0|1    -> toggle auto-restore
        //                    /api/save?now=1       -> restore immediately
        //
        // The client accumulates its own progress in CMAccountSlot.sav, so a run
        // that dies mid-way poisons the next one.  With auto-restore on, every
        // launch of the proxy rewrites the slot to the fixed known-good save.
        if (path.equals("/api/save") && method.equals("POST")) {
            if (q.get("auto") != null) {
                SaveRestore.autoRestore =
                        !("0".equals(q.get("auto")) || "false".equals(q.get("auto")));
            }
            String did = "";
            if ("1".equals(q.get("now")) || "true".equals(q.get("now"))) {
                did = SaveRestore.restore();
            }
            return "{\"ok\":true,\"auto\":" + SaveRestore.autoRestore
                    + ",\"path\":\"" + SaveRestore.SAVE_PATH + "\""
                    + (did.isEmpty() ? "" : ",\"restore\":\"" + did + "\"") + "}";
        }
        if (path.equals("/")) {
            return page();
        }
        return "<!doctype html><meta charset=\"utf-8\"><p>404 " + esc(path) + "</p>";
    }

    /**
     * The /api/status payload.  Lives here rather than in Status so that Status
     * stays pure Java and can be compiled by the desktop packet tests.
     */
    static String statusJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"vpn_running\":").append(ProxyVpnService.isRunning()).append(',');
        sb.append("\"web_running\":").append(LocalWebServer.get().isRunning()).append(',');
        sb.append("\"game_server\":\"").append(Status.jsonEscape(GameServer.hostPort())).append("\",");
        sb.append("\"flows\":").append(TunRelay.flowCount()).append(',');
        sb.append("\"packets_in\":").append(TunRelay.packetsIn()).append(',');
        sb.append("\"packets_out\":").append(TunRelay.packetsOut()).append(',');
        sb.append("\"game_conns\":").append(GameServer.connectionCount()).append(',');
        sb.append("\"message\":\"").append(Status.jsonEscape(ProxyVpnService.lastMessage())).append("\",");
        sb.append("\"skipTutorial\":").append(GameServer.skipTutorial).append(',');
        sb.append("\"users\":").append(UserManager.get().json()).append(',');
        sb.append("\"profiles\":").append(ProfileStore.get().json());
        sb.append('}');
        return sb.toString();
    }

    private String page() {
        String title = "Closers-RTServer";                    // was 本地代理
        String st = "\u72c0\u614b";                                       // 狀態
        String acct = "\u5e33\u865f (User Manager)";                      // 帳號
        String mkUser = "\u5efa\u7acb\u5e33\u865f";                        // 建立帳號
        String refresh = "\u91cd\u65b0\u6574\u7406";                       // 重新整理
        String play = "\u555f\u52d5\u904a\u6232";                          // 啟動遊戲
        String asset = "\u8ce7\u7522\uff08\u91d1\u5e63 / \u9053\u5177 / \u89d2\u8272\u5361\uff09";  // 資產
        String assetHint = "\u6539\u5b8c\u5f8c\u91cd\u65b0\u767b\u5165\u904a\u6232\u5373\u53ef\u751f\u6548\u3002";
        String gold = "\u91d1\u5e63 id/\u6578\u91cf";
        String itemLbl = "\u9053\u5177 id/\u6578\u91cf";
        String charLbl = "\u89d2\u8272\u5361 id";
        String setBtn = "\u8a2d\u5b9a";
        String addBtn = "\u65b0\u589e";
        String resetBtn = "\u91cd\u8a2d\u70ba\u9810\u8a2d";
        String playHint = "\u9078\u597d\u8cc7\u6599\u5305\u5f8c\uff0c"
                + "\u56de\u5230\u624b\u6a5f\u958b\u555f\u904a\u6232\u5373\u53ef\u3002";

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><meta charset=\"utf-8\">");
        sb.append("<meta name=viewport content=\"width=device-width,initial-scale=1\">");
        sb.append("<title>").append(title).append("</title>");
        sb.append("<style>body{font:14px/1.6 system-ui,sans-serif;margin:22px;max-width:780px}");
        sb.append("pre{background:#f4f4f4;padding:10px;border-radius:6px;overflow:auto;");
        sb.append("max-height:320px}.k{color:#666}h2{margin-top:26px;border-top:1px solid #eee;");
        sb.append("padding-top:12px}button,input{font:inherit;padding:7px 11px;border-radius:6px;");
        sb.append("border:1px solid #ccc}button{background:#2563eb;color:#fff;border-color:#2563eb;");
        // Press feedback: every button visibly reacts while held, so a tap
        // cannot look like it did nothing.
        sb.append("cursor:pointer;transition:transform .06s ease,filter .06s ease,box-shadow .06s ease}");
        sb.append("button:hover{filter:brightness(1.08)}");
        sb.append("button:active{transform:scale(.94);filter:brightness(.78);");
        sb.append("box-shadow:inset 0 2px 6px rgba(0,0,0,.45)}");
        sb.append("button:disabled{background:#9aa3ad;border-color:#9aa3ad;cursor:not-allowed}");
        sb.append("button.done{background:#16a34a;border-color:#16a34a}");
        // Toast: fades in, holds, fades out.
        sb.append("#toast{position:fixed;left:50%;bottom:34px;transform:translate(-50%,18px);");
        sb.append("opacity:0;pointer-events:none;background:#111;color:#fff;padding:12px 22px;");
        sb.append("border-radius:26px;font-size:15px;box-shadow:0 8px 24px rgba(0,0,0,.35);");
        sb.append("transition:opacity .32s ease,transform .32s ease;z-index:99}");
        sb.append("#toast.show{opacity:1;transform:translate(-50%,0)}");
        sb.append("</style>");
        sb.append("<div id=toast></div>");
        sb.append("<h1>").append(title).append("</h1>");
        sb.append("<p class=k>127.0.0.1 only</p>");
        sb.append("<h2>服裝 Costume</h2><p>選角色 → 下方列出他真正擁有的服裝 → 點一下設定。設定後請重新登入遊戲；大廳角色模型只在登入時建立，切換角色不會重建。</p>");
        sb.append("角色 <select id=costumeSel></select><div id=costumeList></div><span id=costumeSt class=k></span>");
        sb.append("<h2>宿舍格子 Dorm slots</h2><p>選好 6 格角色後按 Save，重新登入生效。</p>");
        for (int i = 0; i < 6; i++) {
            sb.append("Slot ").append(i + 1).append(" <select id=dorm").append(i).append("></select><br>");
        }
        sb.append("<button onclick=\"saveDorm()\">Save dorm</button> <span id=dormst class=k></span>");
        sb.append("<h2>").append(st).append("</h2><pre id=status>...</pre>");
        sb.append("<h2>").append(acct).append("</h2>");
        sb.append("<input id=uname placeholder=username style=\"width:170px\"> ");
        sb.append("<button onclick=createUser()>").append(mkUser).append("</button> ");
        sb.append("<button onclick=listUsers()>").append(refresh).append("</button>");
        sb.append("<pre id=users></pre>");
        // ---- assets ----------------------------------------------------
        sb.append("<h2>").append(asset).append("</h2>");
        sb.append("<p>").append(assetHint).append("</p>");
        sb.append("<div><b>").append(gold).append("</b> <input id=fundId type=number value=1 style=width:70px> ");
        sb.append("<input id=fundAmt type=number value=1000000 style=width:130px> ");
        sb.append("<button onclick=setFund()>").append(setBtn).append("</button>");
        sb.append(" <button onclick=quickFund(1,1000000)>金幣 +100萬</button>");
        sb.append(" <button onclick=quickFund(2,100000)>稜鏡 +10萬</button></div>");
        // BUG-001 is fixed (14-byte records: u16 len + flags 0x02 + key u16 @+4
        // + amount 8B @+6; key 1 = gold, key 2 = prism).  The old red banner and
        // the disabled button were left over from the broken probe build.
        sb.append("<div style=\"margin-top:4px;font-size:12px;color:#9a9\">");
        sb.append("金幣 = key 1、稜鏡 = key 2。設定後重新登入（或重開遊戲）才會生效。");
        sb.append("</div>");
        // ---- item picker -------------------------------------------------
        sb.append("<div style=\"margin-top:10px\"><b>\u9078\u64c7\u9053\u5177</b><br>");
        sb.append("<select id=itemSel style=\"width:100%;max-width:520px;margin-top:4px\">");
        sb.append("<option value=\"\">\u8f09\u5165\u4e2d\u2026</option></select></div>");
        sb.append("<div style=\"margin-top:6px\">\u6578\u91cf ");
        sb.append("<input id=itemCnt type=number value=1 min=1 style=width:110px> ");
        sb.append("<button onclick=\"bump(1)\">+1</button> ");
        sb.append("<button onclick=\"bump(10)\">+10</button> ");
        sb.append("<button onclick=\"bump(100)\">+100</button> ");
        sb.append("<button style=\"font-weight:bold\" onclick=\"addSel(event)\">\u52a0\u5165\u5e33\u6236</button></div>");
        sb.append("<div style=\"margin-top:6px\"><b>").append(itemLbl).append("</b> <input id=itemId type=number placeholder=id style=\"width:90px\"> ");
        sb.append("<input id=itemCnt2 type=number value=1 style=\"width:80px\"> ");
        sb.append("<button onclick=\"addItem(event)\">").append(addBtn).append("</button></div>");
        sb.append("<div style=\"margin-top:8px\"><b>").append(charLbl).append("</b> <input id=charId placeholder=\"4105\" style=width:110px> ");
        sb.append("<button onclick=addChar()>").append(addBtn).append("</button> ");
        sb.append("<button onclick=resetAssets()>").append(resetBtn).append("</button></div>");
        sb.append("<div style=\"margin-top:10px;padding:8px;border:1px solid #ddd;border-radius:6px\">");
        sb.append("<b>角色等級 / 技能等級</b><br>");
        sb.append("<span class=k>套用到所有角色；設定後請重啟遊戲。</span><br>");
        sb.append("等級 <input id=charLv type=number value=30 min=1 max=60 style=width:70px> ");
        sb.append("<button onclick=setCharLv()>").append(setBtn).append("</button> ");
        sb.append("技能 <input id=skillLv type=number value=3 min=0 max=10 style=width:60px> ");
        sb.append("<button onclick=setSkillLv()>全部設為</button><br>");
        sb.append("經驗 <input id=charExp type=number value=57 style=width:100px> ");
        sb.append("<button onclick=setCharExp()>").append(setBtn).append("</button>");
        sb.append("<span class=k>（Lv30 用 57 ≈ 134/1342）</span>");
        sb.append("<span id=lvState class=k style=\"margin-left:8px\"></span></div>");
        // ---- save export / import / reset -------------------------------
        sb.append("<div style=\"margin-top:10px;padding:8px;border:1px solid #ddd;border-radius:6px\">");
        sb.append("<b>\u5b58\u6a94\u532f\u51fa\uff08\u81ea\u5df1\u4e0b\u8f09\uff09</b><br>");
        sb.append("<span class=k>\u628a\u76ee\u524d\u9032\u5ea6\u5b58\u6210\u4e00\u4efd JSON\uff0c\u53ef\u4ee5\u81ea\u5df1\u4fdd\u5b58\u3002\u4e0d\u6703\u6539\u8b8a\u4f3a\u670d\u5668\u4e0a\u7684\u5b58\u6a94\u3002</span><br>");
        sb.append("<button onclick=\"exportSave()\">\u532f\u51fa\u6211\u7684\u5b58\u6a94</button> ");
        sb.append("<span id=saveExpState class=k></span>");
        sb.append("<div style=\"margin-top:8px\">");
        sb.append("<input type=file id=saveImp accept=\".json,application/json\"> ");
        sb.append("<button onclick=\"importSave()\">\u532f\u5165\u5b58\u6a94</button>");
        sb.append("<span id=saveImpState class=k></span></div>");
        sb.append("<div style=\"margin-top:8px\">");
        sb.append("<button onclick=\"resetSave()\">\u91cd\u8a2d\u5b58\u6a94\uff08\u9084\u539f\u6210\u57fa\u6e96\u9810\u8a2d\uff09</button>");
        sb.append("<span id=saveResetState class=k></span></div></div>");
        sb.append("<pre id=assets></pre>");
        // ---- newbie tutorial -------------------------------------------
        //
        // The account's MainScene byte decides whether the lobby unlocks
        // straight away or sends the player through the tutorial.  Both
        // states are useful (skip it while testing, keep it when a fresh
        // account should behave like retail), so it is a live toggle rather
        // than a build-time decision.
        sb.append("<h2>新手教學 Tutorial</h2>");
        sb.append("<p class=k>跳過 = 直接解鎖大廳；不跳過 = 像全新帳號從頭跑一次"
                  + "（下次登入生效，請重啟遊戲）</p>");
        sb.append("<div><b id=tutState>...</b> ");
        sb.append("<button onclick=\"setTutorial(1)\">跳過 Skip</button> ");
        sb.append("<button onclick=\"setTutorial(0)\">不跳過 Play</button></div>");
        sb.append("<pre id=tut></pre>");
        sb.append("<h2>").append(play).append("</h2><p>").append(playHint).append("</p>");
        sb.append("<div class=k>Log &nbsp;<a href=\"#top\">↑ 回頂端</a></div><input id=logf placeholder=\"costume|favor|growth|opcode（留空＝全部）\" style=\"width:340px\"> <label class=k><input type=checkbox id=loghex> 顯示 hex 長行</label>");
        sb.append("<label class=k style=\"margin-left:10px\"><input type=checkbox id=gameLogOn onchange=\"setGameLog(this.checked)\"> 遊戲端 CM.log 轉發（預設關閉）</label><span id=gameLogSt class=k style=\"margin-left:8px\"></span>");
        sb.append("<pre id=log style=\"max-height:300px\"></pre>");
        sb.append("<h2>服裝 Costume</h2><p>選角色 → 下方列出他真正擁有的服裝 → 點一下設定。設定後請重新登入遊戲；大廳角色模型只在登入時建立，切換角色不會重建。</p>");
        sb.append("角色 <select id=costumeSel></select><div id=costumeList></div><span id=costumeSt class=k></span>");
        sb.append("<h2>宿舍格子 Dorm slots</h2><p>選好 6 格角色後按 Save，重新登入生效。</p>");
        for (int i = 0; i < 6; i++) {
            sb.append("Slot ").append(i + 1).append(" <select id=dorm").append(i).append("></select><br>");
        }
        sb.append("<button onclick=\"saveDorm()\">Save dorm</button> <span id=dormst class=k></span>");
        // ---- language (bottom: set up once, then toggle from the app) ----
        sb.append("<div style=\"border:2px solid #2563eb;border-radius:10px;"
                  + "padding:14px 16px;margin:14px 0\">");
        sb.append("<h2 style=\"margin:0 0 6px;border:0;padding:0\">語言 Language</h2>");
        sb.append("<p class=k style=\"margin:0 0 10px\">切換後請<strong>完全關閉遊戲再重開</strong>："
                  + "客戶端每次啟動都會把 TableFiles.dat 解包成腳本與文字表，重開才會生效。</p>");
        sb.append("<div><b id=langState>...</b><br><br>"
                  + "<button onclick=\"setLang('ko')\" style=\"font-size:15px;padding:10px 18px\">한국어 韓文</button> "
                  + "<button onclick=\"setLang('zh')\" style=\"font-size:15px;padding:10px 18px\">简体中文</button></div>");
        sb.append("<p class=k style=\"margin:12px 0 0\"><b>語言包 zip（建議：內含中文+韓文，第一次上傳這個就好）</b><input type=file id=langzip accept=.zip> "
                  + "<button onclick=\"uploadLangZip()\">上傳 zip</button> <span id=langZipSt class=k></span></p>");
        sb.append("<p class=k style=\"margin:12px 0 0\">語言包（整個 TableFiles.dat）：<input type=file id=langf accept=.dat> "
                  + "<select id=langTarget><option value=zh>中文 zh</option>"
                  + "<option value=ko>韓文 ko</option></select> "
                  + "<button onclick=\"uploadLang()\">上傳</button></p>");
        sb.append("<pre id=lang></pre>");
        sb.append("</div>");

        sb.append("<script>");
        sb.append("async function j(u,o){const r=await fetch(u,o);return await r.text()}");
        // Toast helper: fade in, hold ~1.4s, fade out.
        sb.append("let toastTimer=null;");
        sb.append("function toast(msg){const t=document.getElementById('toast');");
        sb.append("t.textContent=msg;t.classList.add('show');");
        sb.append("clearTimeout(toastTimer);");
        sb.append("toastTimer=setTimeout(()=>t.classList.remove('show'),1500)}");
        // Adds an item and reports the outcome both in the log area and as a toast.
        sb.append("async function addItemTo(id,count,btn){");
        sb.append("if(btn){btn.classList.add('done');setTimeout(()=>btn.classList.remove('done'),600)}");
        sb.append("const txt=await j('/api/asset/item?id='+id+'&count='+count,{method:'POST',body:''});");
        sb.append("document.getElementById('assets').textContent=txt;");
        sb.append("try{const o=JSON.parse(txt);");
        sb.append("const hit=(o.items||[]).find(x=>String(x.id)===String(id));");
        sb.append("toast(o.ok?('\u5df2\u52a0\u5165 id '+id+' \u00d7 '+count+(hit?('\uff08\u76ee\u524d '+hit.count+'\uff09'):'')):'\u52a0\u5165\u5931\u6557');");
        sb.append("}catch(e){toast('\u5df2\u9001\u51fa\u8981\u6c42')}}");
        sb.append("async function refreshLang(){try{const o=JSON.parse(await j('/api/lang'));");
        sb.append("document.getElementById('langState').textContent='目前：'+(o.current==='zh'?'简体中文':'한국어');");
        sb.append("document.getElementById('lang').textContent=JSON.stringify(o,null,1)}catch(e){}}");
        sb.append("async function setLang(v){const t=await j('/api/lang?set='+v,{method:'POST',body:''});");
        sb.append("toast(v==='zh'?'已切換為简体中文，請重開遊戲':'已切換為한국어，請重開遊戲');");
        sb.append("try{document.getElementById('lang').textContent=t}catch(e){}refreshLang()}");
        sb.append("async function uploadLangZip(){var el=document.getElementById('langzip');");
        sb.append("if(!el.files[0]){toast('請先選擇語言包 zip');return}");
        sb.append("document.getElementById('langZipSt').textContent='上傳中…';");
        sb.append("var fd=new FormData();fd.append('f',el.files[0]);");
        sb.append("try{const r=await fetch('/api/lang/importzip',{method:'POST',body:el.files[0]});");
        sb.append("var t=await r.text();");
        sb.append("document.getElementById('langZipSt').textContent=(t.indexOf('\"ok\":true')>=0?'已入庫':t);}catch(e){");
        sb.append("document.getElementById('langZipSt').textContent='失敗';}");
        sb.append("toast('語言包 zip 已處理，請重開遊戲');refreshLang()}");
        sb.append("async function uploadLang(){const el=document.getElementById('langf');");
        sb.append("if(!el.files.length){toast('請先選擇 .dat 檔');return}");
        sb.append("const lang=document.getElementById('langTarget').value;");
        sb.append("toast('上傳中…（35 MB，請稍候）');");
        sb.append("const r=await fetch('/api/lang/upload?lang='+lang,{method:'POST',body:el.files[0]});");
        sb.append("const t=await r.text();document.getElementById('lang').textContent=t;");
        sb.append("toast(t.indexOf('\"ok\":true')>=0?'語言包已上傳，請重開遊戲':'上傳失敗');refreshLang()}");
        sb.append("async function setGameLog(on){const t=await j('/api/logcfg?on='+(on?1:0),{method:'POST',body:''});document.getElementById('gameLogSt').textContent=on?'已開啟（重啟遊戲生效）':'已關閉';toast(on?'遊戲端 log 轉發已開啟':'遊戲端 log 轉發已關閉');try{const o=JSON.parse(t);document.getElementById('gameLogOn').checked=o.enabled}catch(e){}}");
        sb.append("async function initGameLog(){try{const o=JSON.parse(await j('/api/logcfg'));document.getElementById('gameLogOn').checked=o.enabled;document.getElementById('gameLogSt').textContent=o.enabled?('已開啟，'+o.lines+' 行'):'已關閉'}catch(e){}}");
        sb.append("async function tick(){document.getElementById('status').textContent=await j('/api/status');");
        sb.append("var raw=await j('/api/log');var ls=[];try{ls=(JSON.parse(raw).log)||[]}catch(e){ls=String(raw).split('\\n')}");
        sb.append("var f=(document.getElementById('logf')||{}).value||'';var showHex=(document.getElementById('loghex')||{}).checked;");
        sb.append("ls=ls.filter(function(l){return showHex||l.indexOf('hex:')<0});");
        sb.append("if(f){try{var re=new RegExp(f,'i');ls=ls.filter(function(l){return re.test(l)})}catch(e){}}");
        sb.append("document.getElementById('log').textContent=ls.slice(-200).join('\\n')}");
        sb.append("async function createUser(){const n=document.getElementById('uname').value;");
        sb.append("if(!n)return;document.getElementById('users').textContent=");
        sb.append("await j('/api/user/create?name='+encodeURIComponent(n),{method:'POST',body:''})}");
        sb.append("function exportSave(){");
        sb.append("var a=document.createElement('a');");
        sb.append("a.href='/api/save/export?download=1';");
        sb.append("a.download='';");
        sb.append("document.body.appendChild(a);a.click();document.body.removeChild(a);");
        sb.append("document.getElementById('saveExpState').textContent='已下載';");
        sb.append("toast('存檔已匯出')}");
        sb.append("async function importSave(){");
        sb.append("var f=document.getElementById('saveImp').files[0];");
        sb.append("if(!f){toast('請先選擇 JSON 存檔');return}");
        sb.append("if(!confirm('確定要匯入這份存檔？目前進度會被覆蓋（系統會先備份目前的存檔）'))return;");
        sb.append("var t=await f.text();");
        sb.append("try{JSON.parse(t)}catch(e){toast('不是有效的 JSON');return}");
        sb.append("var x=await j('/api/save/import',{method:'POST',body:t});");
        sb.append("document.getElementById('saveImpState').textContent=x;");
        sb.append("toast('已匯入，請重啟遊戲');");
        sb.append("loadAssets()}");
        sb.append("async function resetSave(){");
        sb.append("if(!confirm('確定要把存檔重設成基準預設？目前進度會被清空，建議先匯出備份。'))return;");
        sb.append("var x=await j('/api/save?op=reset',{method:'POST',body:''});");
        sb.append("document.getElementById('saveResetState').textContent=x;");
        sb.append("toast('存檔已重設，請重啟遊戲');");
        sb.append("loadAssets()}");

        sb.append("async function loadAssets(){document.getElementById('assets').textContent=");
        sb.append("await j('/api/assets')}");
        sb.append("async function setFund(){const i=document.getElementById('fundId').value;");
        sb.append("const a=document.getElementById('fundAmt').value;");
        sb.append("document.getElementById('assets').textContent=");
        sb.append("await j('/api/asset/fund?id='+i+'&amount='+a,{method:'POST',body:''})}");
        sb.append("async function quickFund(id,amt){document.getElementById('assets').textContent=");
        sb.append("await j('/api/asset/fund?id='+id+'&amount='+amt,{method:'POST',body:''});");
        sb.append("toast('已設定 '+(id==1?'金幣':'稜鏡')+' = '+amt)}");
        sb.append("function bump(n){const e=document.getElementById('itemCnt');");
        sb.append("e.value=(parseInt(e.value||'0',10)||0)+n}");
        sb.append("async function loadItems(){");
        sb.append("const list=await (await fetch('/api/itemlist')).json();");
        sb.append("const sel=document.getElementById('itemSel');sel.innerHTML='';");
        sb.append("for(const r of list){const o=document.createElement('option');");
        sb.append("o.value=r[0];o.textContent=r[0]+' \u00b7 '+r[1]+' \u00b7 '+r[2]+' ('+r[3]+')';");
        sb.append("sel.appendChild(o)}}");
        sb.append("async function addSel(ev){const sel=document.getElementById('itemSel');");
        sb.append("if(!sel.value){toast('\u8acb\u5148\u9078\u64c7\u9053\u5177');return}");
        sb.append("const c=document.getElementById('itemCnt').value||1;");
        sb.append("await addItemTo(sel.value,c,ev&&ev.target)}");
        sb.append("async function addItem(ev){const i=document.getElementById('itemId').value;");
        sb.append("const c=document.getElementById('itemCnt2').value||1;");
        sb.append("if(!i){toast('\u8acb\u5148\u586b id');return}");
        sb.append("await addItemTo(i,c,ev&&ev.target)}");
        sb.append("async function addChar(){const i=document.getElementById('charId').value;");
        sb.append("if(!i)return;document.getElementById('assets').textContent=");
        sb.append("await j('/api/asset/char?id='+encodeURIComponent(i),{method:'POST',body:''})}");
        sb.append("async function setCharExp(){const v=document.getElementById('charExp').value;");
        sb.append("const txt=await j('/api/charexp?v='+v,{method:'POST',body:''});");
        sb.append("document.getElementById('lvState').textContent='角色經驗已設為 '+v+'（重啟遊戲生效）';");
        sb.append("toast('角色經驗已設為 '+v)}");
        sb.append("async function setCharLv(){const v=document.getElementById('charLv').value;");
        sb.append("const txt=await j('/api/charlevel?v='+v,{method:'POST',body:''});");
        sb.append("document.getElementById('lvState').textContent='等級已設為 '+v+'（重啟遊戲生效）';");
        sb.append("toast('角色等級已設為 '+v)}");
        sb.append("async function setSkillLv(){const v=document.getElementById('skillLv').value;");
        sb.append("const arr=Array(7).fill(v).join(',');");
        sb.append("const txt=await j('/api/charskills?v='+arr,{method:'POST',body:''});");
        sb.append("document.getElementById('lvState').textContent='7 個技能已設為 '+v+' 級（重啟遊戲生效）';");
        sb.append("toast('技能等級已設為 '+v)}");
        sb.append("async function resetAssets(){document.getElementById('assets').textContent=");
        sb.append("await j('/api/asset/reset',{method:'POST',body:''})}");
        sb.append("async function tutState(){const t=JSON.parse(await j('/api/status'))");
        sb.append(";document.getElementById('tutState').textContent=");
        sb.append("t.skipTutorial?'目前：跳過中 (MainScene=7)':'目前：正常播放 (MainScene=4)'}");
        sb.append("async function tutInit(){await tutState();setInterval(tutState,3000)}");
        sb.append("async function setTutorial(v){document.getElementById('tut').textContent=");
        sb.append("await j('/api/tutorial?skip='+v,{method:'POST'})}");
        sb.append("async function listUsers(){document.getElementById('users').textContent=");
        sb.append("await j('/api/users');tick()}");
        sb.append("function loadCostumes(){var c=document.getElementById('costumeSel').value;");
        sb.append("fetch('/api/costumes?char='+c).then(r=>r.json()).then(ids=>{var h='';");
        sb.append("if(!ids.length)h='<span class=k>沒有資料</span>';");
        sb.append("ids.forEach(id=>{h+='<button onclick=\"wearCostume('+c+','+id+')\">'+id+'</button> ';});");
        sb.append("document.getElementById('costumeList').innerHTML=h;});}");
        sb.append("async function wearCostume(c,id){var r=await fetch('/api/costume?char='+c+'&id='+id,{method:'POST'});var j=await r.json();");
        sb.append("document.getElementById('costumeSt').textContent='char '+c+' -> '+id+' : '+j.result+'（重登遊戲後生效：大廳模型只在登入時建立）';}");
sb.append("var DCH=[];fetch('/api/charlist').then(r=>r.json()).then(d=>{DCH=d;");
        sb.append("for(var i=0;i<6;i++){var e=document.getElementById('dorm'+i);");
        sb.append("e.innerHTML='<option value=0>(empty)</option>'+d.map(c=>'<option value='+c[0]+'>'+c[0]+'  '+c[2]+' / '+c[1]+'</option>').join('');}");
        sb.append("return fetch('/api/dorm')}).then(r=>r.json()).then(j=>{var v=(j.ids||'').split(',');");
        sb.append("for(var i=0;i<6;i++){var e=document.getElementById('dorm'+i);if(v[i])e.value=v[i];}});");
        sb.append("async function saveDorm(){var q=[];for(var i=0;i<6;i++)q.push('s'+i+'='+document.getElementById('dorm'+i).value);");
        sb.append("var r=await fetch('/api/dorm?'+q.join('&'),{method:'POST'});var j=await r.json();");
        sb.append("document.getElementById('dormst').textContent='saved: '+j.ids+'  (relog)';}");

sb.append("tick();listUsers();loadAssets();loadItems();tutInit();initGameLog();refreshLang();setInterval(tick,2000);");
        // Costume picker: runs on a timer so any failure cannot break the rest.
        sb.append("setTimeout(function(){try{var cs=document.getElementById('costumeSel');");
        sb.append("if(!cs||!DCH||!DCH.length)return;cs.innerHTML=DCH.map(function(x){");
        sb.append("return '<option value='+x[0]+'>'+x[0]+' '+x[2]+'</option>'}).join('');");
        sb.append("cs.onchange=loadCostumes;loadCostumes();}catch(e){}},1200);");
        sb.append("</script>");
        return sb.toString();
    }

    // ---- small helpers ---------------------------------------------------


    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String url(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
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
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return out;
    }

    /** Six dorm-slot pickers with Korean + Chinese names. */
    private String dormPage() {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><meta charset=utf-8><title>Dorm slots</title>");
        b.append("<style>body{font-family:sans-serif;background:#111;color:#eee;padding:16px}"
                + "select{font-size:15px;margin:4px 0;width:360px;background:#222;color:#eee;border:1px solid #444}"
                + "button{font-size:15px;padding:6px 18px;margin-top:10px}</style>");
        b.append("<h2>宿舍格子 / Dorm slots</h2><p>選好後按 Save，重新登入遊戲生效。</p>");
        for (int i = 0; i < 6; i++) {
            b.append("Slot ").append(i + 1).append(" <select id=s").append(i).append("></select><br>");
        }
        b.append("<button onclick=save()>Save</button> <span id=st></span>");
        b.append("<script>");
        b.append("fetch('/api/charlist').then(r=>r.json()).then(d=>{");
        b.append("for(var i=0;i<6;i++){var s=document.getElementById('s'+i);");
        b.append("s.innerHTML='<option value=0>(empty)</option>'+d.map(c=>'<option value='+c[0]+'>'+c[0]+'  '+c[2]+' / '+c[1]+'</option>').join('');}});");
        b.append("fetch('/api/dorm').then(r=>r.json()).then(j=>{var v=(j.ids||'').split(',');");
        b.append("for(var i=0;i<6;i++){var s=document.getElementById('s'+i);if(v[i])s.value=v[i];}});");
        b.append("function save(){var q=[];for(var i=0;i<6;i++)q.push('s'+i+'='+document.getElementById('s'+i).value);");
        b.append("fetch('/api/dorm?'+q.join('&'),{method:'POST'}).then(r=>r.json())");
        b.append(".then(j=>document.getElementById('st').textContent='saved: '+j.ids);}");
        b.append("</script>");
        return b.toString();
    }

    private String charListJson() {
        StringBuilder b = new StringBuilder("[");
        String[] raw = DormCatalog.RAW;
        for (int i = 0; i < raw.length; i++) {
            int a = raw[i].indexOf(124);
            int c = raw[i].indexOf(124, a + 1);
            if (i > 0) b.append(",");
            b.append("[").append(raw[i].substring(0, a)).append(",\"")
             .append(esc(raw[i].substring(a + 1, c))).append("\",\"")
             .append(esc(raw[i].substring(c + 1))).append("\"]");
        }
        return b.append("]").toString();
    }


}
