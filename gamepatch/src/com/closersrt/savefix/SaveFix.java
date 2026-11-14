package com.closersrt.savefix;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restores the fixed save on every launch, and unpacks the bundled content
 * archive into the game's own external files dir.
 *
 * UE4 does NOT unpack assets/main.obb.png by itself -- measured: installing an
 * APK that carries it leaves files/ empty and the game never reaches the lobby.
 * Android's standard obb path is not read either.  So we do it here, from the
 * game's own process, which is the only place allowed to write
 * /sdcard/Android/data/<pkg>/files.
 */
public final class SaveFix {

    private static final String TAG = "ClosersSaveFix";
    private static final String SAVE_REL = "SaveGames/CMAccountSlot.sav";
    private static final String GOLDEN_ASSET = "savefix/CMAccountSlot.sav";
    private static final String OBB_ASSET = "content.zip";
    private static final String MARKER = ".obb_unpacked";

    /**
     * Content ships as content.zip, content2.zip, content3.zip ... so no single
     * zip entry crosses 4 GB (past it Python writes zip64 and zipalign /
     * apksigner reject the archive as "Malformed").  Keep going until a name is
     * missing.
     */
    private static String partName(int i) {
        return i == 0 ? OBB_ASSET : ("content" + (i + 1) + ".zip");
    }

    private SaveFix() { }

    public static void install(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                Log.w(TAG, "外部目錄不可用，略過");
                return;
            }
            unpackObbDir(ctx, dir);
            unpackContent(ctx, dir);
            restoreSave(ctx, dir);
            startLogPump(dir);
        } catch (Throwable t) {
            Log.e(TAG, "例外: " + t);
        }
    }

    // ------------------------------------------------------------------
    // CM.log -> local proxy (/api/gamelog)
    //
    // On a retail phone CM.log sits in the game's own
    // /sdcard/Android/data/<pkg>/files/... directory as 0600; adb cannot read
    // it and the user's file managers could not copy it out.  The game itself
    // CAN read it, so we tail the file here and POST the new bytes to the
    // proxy's loopback web console.  Everything stays on the device.
    // ------------------------------------------------------------------

    // The game manifest has no android:usesCleartextTraffic, so Android's
    // default network security policy blocks http:// from inside this process.
    // Push over the proxy's TLS patch port instead; the certificate is the
    // proxy's own self-signed one, which this TrustAll socket accepts.
    private static final String LOG_PUSH_URL =
            "https://127.0.0.1:8443/api/gamelog";
    private static final String LOG_REL =
            "UE4Game/CM/CM/Saved/Logs/CM.log";
    /** Only used when the proxy is not running: buffer here, resend next boot. */
    private static final String SPOOL = "gamelog-spool.txt";
    private static final int MAX_SPOOL = 4 * 1024 * 1024;

    private static void startLogPump(final File dir) {
        // The pump is OFF unless the local server asks for it.
        //
        // Cost when enabled: one File.length() every 2 s (negligible) plus a
        // TLS POST + String copy whenever CM.log grows -- and UE4 writes a lot
        // of lines during a battle, so that part is real work on the game
        // thread's process.  Feature is only needed while diagnosing, so the
        // default is off and the server toggles it at runtime.
        final boolean enabled = logPumpEnabled();
        Log.i(TAG, "log pump enabled=" + enabled);
        if (!enabled) return;
        // Named static class, not an anonymous Runnable: d8 8.2 (build-tools
        // 34) aborts with an internal NPE when handed javac's Foo$1.class.
        Thread t = new Thread(new LogPump(dir), "savefix-log");
        t.setDaemon(true);
        t.start();
    }

    /** Ask the local server whether log forwarding is wanted (default no). */
    private static boolean logPumpEnabled() {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL("https://127.0.0.1:8443/api/logcfg");
            c = (java.net.HttpURLConnection) openTrustAll(u);
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return false;
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[512];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            is.close();
            String s = bo.toString("UTF-8");
            // {"enabled":true,...}
            return s.indexOf("\"enabled\":true") >= 0;
        } catch (Throwable t) {
            Log.i(TAG, "logcfg unavailable, log pump stays off: " + t);
            return false;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    private static final class LogPump implements Runnable {
        private final File dir;
        LogPump(File dir) { this.dir = dir; }
        @Override public void run() {
            try {
                pumpLog(dir);
            } catch (Throwable e) {
                Log.w(TAG, "log pump 結束: " + e);
            }
        }
    }

    private static void pumpLog(File dir) throws Exception {
        File log = new File(dir, LOG_REL);
        Log.i(TAG, "log pump 開始: " + log);
        // Wait for UE4 to create the log (SaveFix runs before the engine).
        for (int i = 0; i < 120 && !log.exists(); i++) {
            Thread.sleep(500);
        }
        if (!log.exists()) {
            Log.i(TAG, "找不到 CM.log，log pump 略過");
            return;
        }
        Log.i(TAG, "CM.log 存在, " + log.length() + " bytes");

        // Warm up: send the existing tail once so the proxy has the startup
        // portion even if the user only asks later.
        long pos = 0;
        flushSpool(dir);
        long len = log.length();
        if (len > 0) {
            long from = Math.max(0, len - 512 * 1024);
            pos = from;
            sendFrom(dir, log, from, len);
        }

        while (true) {
            Thread.sleep(2000);
            File cur = new File(dir, LOG_REL);
            if (!cur.exists()) {
                continue;                       // log rotated during a restart
            }
            long size = cur.length();
            if (size < pos) {                   // truncated/rotated
                pos = 0;
            }
            if (size == pos) {
                continue;
            }
            // Cap a single read so a huge burst cannot blow the heap.
            long end = Math.min(size, pos + 256 * 1024);
            byte[] buf = new byte[(int) (end - pos)];
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(cur, "r");
            try {
                raf.seek(pos);
                raf.readFully(buf);
            } finally {
                raf.close();
            }
            pos = end;
            String chunk = new String(buf, "UTF-8");
            if (!post(LOG_PUSH_URL, chunk)) {
                spool(dir, chunk);
            }
        }
    }

    /** Read [from,to) of f and push it, spooling when the proxy is down. */
    private static void sendFrom(File dir, File f, long from, long to) {
        try {
            int n = (int) (to - from);
            if (n <= 0) return;
            byte[] buf = new byte[n];
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.seek(from);
                raf.readFully(buf);
            } finally {
                raf.close();
            }
            String chunk = new String(buf, "UTF-8");
            boolean ok = post(LOG_PUSH_URL, chunk);
            Log.i(TAG, "log push " + chunk.length() + " chars -> " + ok);
            if (!ok) {
                spool(dir, chunk);
            }
        } catch (Throwable ignored) { }
    }

    /** POST a text chunk; returns false when the proxy is not reachable. */
    private static boolean post(String url, String body) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) openTrustAll(new java.net.URL(url));
            c.setConnectTimeout(2000);
            c.setReadTimeout(4000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            byte[] b = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(b);
            } finally {
                os.close();
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            Log.w(TAG, "log push 失敗: " + t);
            return false;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Open a connection that accepts the proxy's self-signed HTTPS cert. */
    private static java.net.URLConnection openTrustAll(java.net.URL u) throws Exception {
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            return u.openConnection();
        }
        final javax.net.ssl.TrustManager[] trustAll =
                new javax.net.ssl.TrustManager[]{new TrustAllManager()};
        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        javax.net.ssl.HttpsURLConnection h =
                (javax.net.ssl.HttpsURLConnection) u.openConnection();
        h.setSSLSocketFactory(sc.getSocketFactory());
        h.setHostnameVerifier(new TrustAllHosts());
        return h;
    }

    /** Accepts any server certificate: this only ever talks to 127.0.0.1. */
    private static final class TrustAllManager implements javax.net.ssl.X509TrustManager {
        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

    private static final class TrustAllHosts implements javax.net.ssl.HostnameVerifier {
        @Override public boolean verify(String host, javax.net.ssl.SSLSession s) { return true; }
    }

    private static void spool(File dir, String chunk) {
        try {
            File f = new File(dir, SPOOL);
            if (f.length() > MAX_SPOOL) {
                f.delete();
            }
            FileOutputStream fo = new FileOutputStream(f, true);
            try {
                fo.write(chunk.getBytes("UTF-8"));
            } finally {
                fo.close();
            }
            // Make the spool world-readable while diagnosing on a retail
            // device: the default 0600 (owned by the game's uid) is why adb
            // and every file manager could not copy CM.log out.
            f.setReadable(true, false);
        } catch (Throwable ignored) { }
    }

    private static void flushSpool(File dir) {
        try {
            File f = new File(dir, SPOOL);
            if (!f.exists() || f.length() == 0) {
                return;
            }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fi = new java.io.FileInputStream(f);
            try {
                int read = 0, r;
                while (read < b.length && (r = fi.read(b, read, b.length - read)) > 0) {
                    read += r;
                }
            } finally {
                fi.close();
            }
            if (post(LOG_PUSH_URL, new String(b, "UTF-8"))) {
                f.delete();
                Log.i(TAG, "已補送離線期間的 log: " + b.length + " bytes");
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Android Expansion Files route.  An APK cannot carry the 3.7 GB PakCache
     * (the archive would exceed 4 GB and the tools reject the zip64 result), so
     * large content can instead ship as
     *   /sdcard/Android/obb/<pkg>/main.<ver>.<pkg>.obb
     * which is a plain ZIP we unpack exactly like the in-APK parts.
     */
    private static void unpackObbDir(Context ctx, File dir) {
        try {
            File obbRoot = new File(android.os.Environment
                    .getExternalStorageDirectory(), "Android/obb/"
                    + ctx.getPackageName());
            File[] obbs = obbRoot.listFiles();
            if (obbs == null || obbs.length == 0) {
                return;
            }
            long total = 0;
            for (File f : obbs) {
                if (f.isFile()) {
                    total += f.length();
                }
            }
            File marker = new File(dir, ".obb_dir_unpacked");
            if (marker.exists() && marker.length() > 0
                    && readLong(marker) == total) {
                Log.i(TAG, "obb 目錄已解包過 (" + total + " bytes)");
                return;
            }
            Log.i(TAG, "開始解包 obb 目錄: " + total + " bytes, "
                    + obbs.length + " 個檔案");
            long t0 = android.os.SystemClock.elapsedRealtime();
            int n = 0;
            for (File f : obbs) {
                if (!f.isFile()) {
                    continue;
                }
                ZipInputStream zis = new ZipInputStream(
                        new java.io.BufferedInputStream(
                                new java.io.FileInputStream(f), 1024 * 1024));
                try {
                    byte[] buf = new byte[256 * 1024];
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) {
                            continue;
                        }
                        File out = new File(dir, e.getName());
                        File parent = out.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }
                        OutputStream os = new FileOutputStream(out);
                        try {
                            int r;
                            while ((r = zis.read(buf)) > 0) {
                                os.write(buf, 0, r);
                            }
                        } finally {
                            os.close();
                        }
                        n++;
                    }
                } finally {
                    zis.close();
                }
            }
            writeLong(marker, total);
            Log.i(TAG, "obb 目錄解包完成: " + n + " 個檔案, "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
        } catch (Throwable t) {
            Log.w(TAG, "obb 目錄解包失敗: " + t);
        }
    }

    private static long readLong(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fi = new java.io.FileInputStream(f);
            try {
                int read = 0, r;
                while (read < b.length
                        && (r = fi.read(b, read, b.length - read)) > 0) {
                    read += r;
                }
            } finally {
                fi.close();
            }
            return Long.parseLong(new String(b, "UTF-8").trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void writeLong(File f, long v) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(Long.toString(v).getBytes("UTF-8"));
        } finally {
            fo.close();
        }
    }

    /** Unpack assets/content*.zip into dir, once per content version. */
    private static void unpackContent(Context ctx, File dir) throws Exception {
        // Total size of every part, used as the "already unpacked" stamp.
        long totalSize = 0;
        int parts = 0;
        for (int i = 0; i < 32; i++) {
            android.content.res.AssetFileDescriptor afd = null;
            long len;
            try {
                afd = ctx.getAssets().openFd(partName(i));
                len = afd.getLength();
            } catch (Throwable t) {
                break;                       // no such part -> end of the list
            } finally {
                if (afd != null) { try { afd.close(); } catch (Throwable ignored) { } }
            }
            totalSize += len;
            parts++;
        }
        if (parts == 0) {
            Log.i(TAG, "沒有內含內容包，略過解包");
            return;   // no bundled content -- normal for the plain patched build
        }

        File marker = new File(dir, MARKER);
        if (marker.exists() && marker.length() > 0) {
            try {
                byte[] b = new byte[(int) marker.length()];
                java.io.FileInputStream fi = new java.io.FileInputStream(marker);
                try {
                    int read = 0, r;
                    while (read < b.length && (r = fi.read(b, read, b.length - read)) > 0) {
                        read += r;
                    }
                } finally {
                    fi.close();
                }
                if (Long.parseLong(new String(b, "UTF-8").trim()) == totalSize) {
                    Log.i(TAG, "內容包已解包過 (" + totalSize + " bytes, "
                            + parts + " 份)");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        Log.i(TAG, "開始解包內容包: " + totalSize + " bytes, " + parts + " 份");
        long t0 = android.os.SystemClock.elapsedRealtime();
        int n = 0;
        for (int i = 0; i < parts; i++) {
            InputStream raw = ctx.getAssets().open(partName(i));
            ZipInputStream zis = new ZipInputStream(raw);
            try {
                byte[] buf = new byte[256 * 1024];
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    File out = new File(dir, e.getName());
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    OutputStream os = new FileOutputStream(out);
                    try {
                        int r;
                        while ((r = zis.read(buf)) > 0) {
                            os.write(buf, 0, r);
                        }
                    } finally {
                        os.close();
                    }
                    n++;
                }
            } finally {
                zis.close();
            }
            Log.i(TAG, "  " + partName(i) + " 完成");
        }
        FileOutputStream fo = new FileOutputStream(marker);
        try {
            fo.write(Long.toString(totalSize).getBytes("UTF-8"));
        } finally {
            fo.close();
        }
        Log.i(TAG, "內容包解包完成: " + n + " 個檔案, "
                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /** Copy the bundled save over whatever the game left behind. */
    private static void restoreSave(Context ctx, File dir) throws Exception {
        File saveGames = new File(dir, "SaveGames");
        if (!saveGames.exists() && !saveGames.mkdirs()) {
            Log.w(TAG, "無法建立 SaveGames");
            return;
        }
        File slot = new File(saveGames, "CMAccountSlot.sav");
        File tmp = new File(saveGames, "CMAccountSlot.sav.tmp");

        InputStream in = ctx.getAssets().open(GOLDEN_ASSET);
        OutputStream os = new FileOutputStream(tmp);
        int copied = 0;
        try {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
                copied += r;
            }
        } finally {
            os.close();
            in.close();
        }

        if (slot.exists()) {
            File backup = new File(saveGames, "CMAccountSlot.sav.pre-restore");
            if (!backup.exists()) {
                slot.renameTo(backup);
            } else {
                slot.delete();
            }
        }
        if (tmp.renameTo(slot)) {
            Log.i(TAG, "固定存檔已套用: " + copied + " bytes");
        } else {
            Log.w(TAG, "套用固定存檔失敗");
        }
    }
}
