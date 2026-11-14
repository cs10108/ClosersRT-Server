package com.closersrt.server;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Language pack selection and staging for the game-side switcher.
 *
 * ARCHITECTURE (both halves matter)
 *   The proxy cannot write into the game's external files directory, so the
 *   actual swap happens inside the game process (com.closersrt.savefix
 *   .LanguageFix).  This class only owns the two things that process reads:
 *
 *     <proxy files>/lang.txt                 "ko" or "zh"
 *     <proxy files>/langpacks/<lang>.dat     a whole TableFiles.dat
 *
 *   The game process reads those two paths directly off the shared storage
 *   tree, which needs no permission on either side.  Reopening the game after
 *   a switch is what makes the change take effect, because the client unpacks
 *   TableFiles.dat at startup.
 *
 * WHY THE PACK IS STAGED RATHER THAN SHIPPED
 *   A pack is 35 MB.  Shipping several inside a 435 KB helper APK is not an
 *   option, so packs are pushed once into the staging directory (from the web
 *   console or with adb) and reused.  The chosen language is remembered, so a
 *   later reinstall of the game still starts in the selected language.
 */
public final class Language {

    public static final String KO = "ko";
    public static final String ZH = "zh";

    private static final String PREFS = "closersrt_lang";
    private static final String KEY_LANG = "lang";
    private static final String SIDECAR = "lang.txt";
    private static final String PACK_DIR = "langpacks";

    private static Context appCtx;

    private Language() { }

    public static synchronized void init(Context ctx) {
        if (ctx != null) {
            appCtx = ctx.getApplicationContext();
        }
        // Keep the sidecar in step with the preference on every start, so a
        // wiped staging directory cannot silently leave the game in a
        // language the console no longer shows.
        sync();
    }

    private static SharedPreferences prefs() {
        return appCtx == null ? null
                : appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String current() {
        SharedPreferences p = prefs();
        if (p == null) {
            return KO;
        }
        String v = p.getString(KEY_LANG, KO);
        return ZH.equals(v) ? ZH : KO;
    }

    public static synchronized boolean set(String lang) {
        if (!ZH.equals(lang) && !KO.equals(lang)) {
            return false;
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return false;
        }
        p.edit().putString(KEY_LANG, lang).commit();
        boolean ok = sync();
        // Switching only takes effect when the game re-applies the container on
        // its next start; that copy is gated by .langfix.
        clearGameMarker();
        return ok;
    }

    /**
     * Remove the game-side .langfix marker so LanguageFix re-applies the pack on
     * the next launch.
     *
     * The proxy cannot write inside another app's Android/data directory on
     * Android 11+, so this is best effort: when it fails the game will keep the
     * old pack until the marker is cleared by other means.  Switching language
     * without this appears to do nothing, which is worse than an honest failure.
     */
    public static boolean clearGameMarker() {
        try {
            File ext = android.os.Environment.getExternalStorageDirectory();
            File marker = new File(ext,
                    "Android/data/com.naddicgames.projectm/files/.langfix");
            if (marker.isFile()) {
                boolean ok = marker.delete();
                Status.log("lang: game marker delete -> " + ok);
                return ok;
            }
            return true;
        } catch (Throwable t) {
            Status.log("lang: marker delete failed: " + t);
            return false;
        }
    }

    /** Write lang.txt so the game process can see the choice. */
    private static boolean sync() {
        File dir = stagingDir();
        if (dir == null) {
            return false;
        }
        File f = new File(dir, SIDECAR);
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(current().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            return true;
        } catch (Throwable t) {
            Status.log("lang: sidecar write failed: " + t);
            return false;
        }
    }

    public static File stagingDir() {
        if (appCtx == null) {
            return null;
        }
        File dir = appCtx.getExternalFilesDir(null);
        if (dir == null) {
            return null;
        }
        File packs = new File(dir, PACK_DIR);
        if (!packs.isDirectory() && !packs.mkdirs()) {
            return null;
        }
        return dir;
    }

    public static File packFile(String lang) {
        File dir = stagingDir();
        if (dir == null) {
            return null;
        }
        return new File(new File(dir, PACK_DIR), lang + ".dat");
    }

    /** Copy a pack under its language name and select it. */
    public static synchronized long stage(String lang, InputStream in) throws Exception {
        if (!ZH.equals(lang) && !KO.equals(lang)) {
            throw new IllegalArgumentException("unknown language " + lang);
        }
        File dst = packFile(lang);
        if (dst == null) {
            throw new java.io.IOException("no staging directory");
        }
        File tmp = new File(dst.getParentFile(), lang + ".dat.part");
        FileOutputStream out = new FileOutputStream(tmp);
        long total = 0;
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new java.io.IOException("rename failed: " + dst);
        }
        Status.log("lang: staged " + lang + " pack (" + total + " bytes)");
        set(lang);
        return total;
    }

    /** Copy a sidecar (currently only the map) under its language name. */
    public static synchronized long stageSidecar(String lang, String ext,
                                                 InputStream in) throws Exception {
        File dst = sidecarFile(lang, ext);
        if (dst == null) {
            throw new java.io.IOException("no staging directory");
        }
        File tmp = new File(dst.getParentFile(), lang + "." + ext + ".part");
        FileOutputStream out = new FileOutputStream(tmp);
        long total = 0;
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            try { out.close(); } catch (Throwable ignored) { }
            try { in.close(); } catch (Throwable ignored) { }
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new java.io.IOException("rename failed: " + dst);
        }
        Status.log("lang: staged " + lang + "." + ext + " (" + total + " bytes)");
        return total;
    }

    /**
     * Path of a pack sidecar, i.e. langpacks/&lt;lang&gt;.&lt;ext&gt;.
     *
     * Only the map is published: it has to travel with the container because a
     * translated entry changes length, and the client uses the map to split the
     * container.
     */
    public static File sidecarFile(String lang, String ext) {
        File dir = stagingDir();
        if (dir == null) {
            return null;
        }
        if (!ZH.equals(lang) && !KO.equals(lang)) {
            return null;
        }
        return new File(new File(dir, PACK_DIR), lang + "." + ext);
    }

    /**
     * Import a language pack from a ZIP chosen by the user.
     *
     * Accepted layouts (any depth, matched by suffix):
     *   TableFiles.dat / TableFiles.zh.dat / zh.dat      -> the container
     *   TableFiles.map / TableFiles.zh.map / zh.map      -> the index
     *
     * The pair is staged under langpacks/zh.{dat,map} and zh becomes the
     * selected language, so a first-time user picks their zip once and never
     * has to upload again.
     */
    public static String importZip(java.io.InputStream zipIn) {
        java.util.zip.ZipInputStream zis = null;
        java.io.File tmpDir = null;
        try {
            if (zipIn == null) return "{\"ok\":false,\"err\":\"no stream\"}";
            tmpDir = new java.io.File(appCtx.getCacheDir(), "langimp");
            deleteTree(tmpDir);
            if (!tmpDir.mkdirs()) return "{\"ok\":false,\"err\":\"no cache dir\"}";

            // A bundle can carry any subset of {zh, ko} x {dat, map}.  Names are
            // matched by suffix so both "TableFiles.zh.dat" and "TableFiles.dat"
            // (bare = zh) work, and the Korean original rides in the same zip,
            // which is what lets a no-root user restore the shipped language
            // later without adb.
            java.util.Map<String, java.io.File> dats  = new java.util.HashMap<String, java.io.File>();
            java.util.Map<String, java.io.File> maps  = new java.util.HashMap<String, java.io.File>();
            java.util.List<String> order = new java.util.ArrayList<String>();

            zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(zipIn, 1 << 16));
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                String lower = name.toLowerCase(java.util.Locale.US);
                boolean isDat = lower.endsWith(".dat");
                boolean isMap = lower.endsWith(".map");
                if (!isDat && !isMap) continue;
                String lang = ZH;
                if (lower.contains(".ko.") || lower.startsWith("ko.")
                        || lower.contains("_ko.") || lower.contains("korean")) lang = KO;
                java.io.File out = new java.io.File(tmpDir, lang + (isDat ? ".dat" : ".map"));
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                try {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = zis.read(buf)) > 0) fo.write(buf, 0, n);
                } finally {
                    fo.close();
                }
                if (isDat) {
                    if (!dats.containsKey(lang)) order.add(lang);
                    dats.put(lang, out);
                } else {
                    maps.put(lang, out);
                }
            }
            if (dats.isEmpty()) {
                return "{\"ok\":false,\"err\":\"zip has no .dat\"}";
            }
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"langs\":{");
            boolean first = true;
            for (String lang : order) {
                java.io.File dat = dats.get(lang);
                if (dat == null || !dat.isFile() || dat.length() < 1024) continue;
                long dlen = stage(lang, new java.io.FileInputStream(dat));
                long mlen = 0;
                java.io.File map = maps.get(lang);
                if (map != null && map.isFile()) {
                    mlen = stageSidecar(lang, "map", new java.io.FileInputStream(map));
                }
                if (!first) sb.append(',');
                first = false;
                sb.append("\"").append(lang).append("\":{\"dat\":").append(dlen)
                  .append(",\"map\":").append(mlen).append('}');
                Status.log("lang: imported " + lang + " dat " + dlen + " / map " + mlen);
            }
            sb.append("}}");
            // Select whichever of the staged languages the user most likely
            // wants: keep the current choice when it is still installed, else
            // prefer zh.
            if (!hasPack(current())) set(current());
            return sb.toString();
        } catch (Throwable t) {
            Status.log("lang: import failed: " + t);
            return "{\"ok\":false,\"err\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        } finally {
            try { if (zis != null) zis.close(); } catch (Throwable ignored) { }
            try { if (zipIn != null) zipIn.close(); } catch (Throwable ignored) { }
            if (tmpDir != null) deleteTree(tmpDir);
        }
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** True when <lang>.dat is staged and non-trivial. */
    public static boolean hasPack(String lang) {
        File f = packFile(lang);
        return f != null && f.isFile() && f.length() > 1024;
    }

    /** True when a Chinese pack is already staged (skip re-upload). */
    public static boolean hasZhPack() {
        File f = packFile(ZH);
        return f != null && f.isFile() && f.length() > 1024;
    }

    /** Add zh to the console JSON only when it is actually installed. */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"current\":\"").append(current()).append('"');
        sb.append(",\"zhInstalled\":").append(hasZhPack());
        File zh = packFile(ZH);
        sb.append(",\"zhBytes\":").append(zh != null && zh.isFile() ? zh.length() : 0);
        sb.append('}');
        return sb.toString();
    }

    /** Report what is currently installed, for the web console. */
    public static String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"current\":\"").append(current()).append('"');
        sb.append(",\"packDir\":\"").append(escape(stagingDir() == null
                ? "" : stagingDir().getAbsolutePath())).append('"');
        sb.append(",\"packs\":{");
        File ko = packFile(KO);
        File zh = packFile(ZH);
        sb.append("\"ko\":").append(ko != null && ko.isFile() ? ko.length() : 0);
        sb.append(",\"zh\":").append(zh != null && zh.isFile() ? zh.length() : 0);
        sb.append("}}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
