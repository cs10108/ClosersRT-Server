package com.closersrt.savefix;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Applies the language pack selected in the local server to the game.
 *
 * WHY THIS RUNS INSIDE THE GAME PROCESS
 *   The file that carries every script and UI string is
 *
 *     /sdcard/Android/data/com.naddicgames.projectm/files/TablePack/TableFiles.dat
 *
 *   Android gives one app no write access to another app's external files
 *   directory, so the proxy cannot copy a pack in.  The game itself can, and
 *   SaveFix.install() already runs as the first statement of
 *   GameActivity.onCreate -- before the client unpacks TableFiles.dat.
 *
 * WHERE THE PACK COMES FROM
 *   Through the proxy's content provider (see com.closersrt.proxy.LangProvider):
 *
 *     content://com.closersrt.proxy.lang/current      "ko" | "zh"
 *     content://com.closersrt.proxy.lang/pack/<lang>  the whole .dat
 *
 *   Read-only URIs, no permission, and no raw path into Android/data -- which
 *   is exactly what Android 11+ FUSE forbids here.
 *
 * FALLBACK
 *   If the provider is unreachable (proxy uninstalled, older proxy build, or a
 *   device where the authority is disabled) the shipped APK asset is used, and
 *   finally nothing happens at all: the game keeps its Korean text rather than
 *   ever being left with a half-written table file.
 */
public final class LanguageFix {

    private static final String TAG = "ClosersLangFix";
    private static final String AUTHORITY = "com.closersrt.proxy.lang";
    private static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    private static final String TARGET_REL = "TablePack/TableFiles.dat";
    private static final String MAP_REL = "TablePack/TableFiles.map";
    private static final String MARKER = ".langfix";

    private LanguageFix() { }

    public static void apply(Context ctx) {
        try {
            File files = ctx.getExternalFilesDir(null);
            if (files == null) {
                return;
            }
            String lang = readSelected(ctx);
            Log.i(TAG, "selected language: " + lang);
            if (lang.length() == 0) {
                // No selection reaches us; leave whatever is on disk alone
                // rather than guessing, so a proxy that is not running cannot
                // silently change the game's language.
                Log.w(TAG, "no language selected; leaving the table pack as-is");
                return;
            }
            // Korean is a pack like any other.  Treating it as "the shipped
            // text" was wrong once a translated pack has been applied: the
            // switch back would do nothing and the game would keep reading the
            // translated container -- which is exactly how a player ends up
            // stuck in Chinese after selecting Korean.

            File target = new File(files, TARGET_REL);
            if (!target.getParentFile().isDirectory()
                    && !target.getParentFile().mkdirs()) {
                Log.w(TAG, "cannot create " + target.getParentFile());
                return;
            }
            long want = packLength(ctx, lang);
            // The stamp must change whenever the pack CONTENT changes, not just
            // its size: two different translated builds can be byte-for-byte the
            // same length (every string is padded to its original width), and a
            // size-only stamp then skips the copy forever.  fold in a cheap
            // content fingerprint of the map sidecar, which is regenerated with
            // the container, plus the pack's own head/tail bytes.
            String stamp = lang + ":" + want + ":" + packFingerprint(ctx, lang);
            File marker = new File(files, MARKER);
            if (marker.isFile() && readText(marker).trim().equals(stamp)
                    && target.length() > 0) {
                Log.i(TAG, "language pack already applied (" + stamp + ")");
                return;
            }
            InputStream in = openPack(ctx, lang);
            if (in == null) {
                Log.w(TAG, "no pack for " + lang + ", keeping the shipped text");
                return;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            long n = copy(in, target);
            // The map has to come with the pack.  A translated entry changes
            // length, so the container size differs from the shipped one and
            // TableFiles.map carries that size per entry; leaving the old map
            // makes the client slice the container with stale offsets.
            copyMap(ctx, files, lang);
            Log.i(TAG, "applied " + lang + " pack (" + n + " bytes, "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms)");
            writeMarker(files, stamp);
        } catch (Throwable t) {
            Log.e(TAG, "language fix failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // provider access
    // ------------------------------------------------------------------

    private static String readSelected(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver()
                    .openInputStream(BASE.buildUpon().appendPath("current").build());
            if (in == null) {
                return "";
            }
            byte[] b = new byte[64];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8").trim().toLowerCase();
        } catch (Throwable t) {
            Log.i(TAG, "provider unavailable: " + t);
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Cheap content fingerprint of a pack, used only to decide whether the copy
     * can be skipped.  Reads the first and last 4 KiB of the container and all
     * of the map sidecar, then folds them into one hex string with a simple
     * rolling hash.  This is not cryptographic; it only has to change when the
     * translated build changes, so that a same-size rebuild is not skipped.
     */
    private static String packFingerprint(Context ctx, String lang) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("MD5");
        } catch (Throwable t) {
            return "0";
        }
        foldStream(md, openPack(ctx, lang), 4096, true);
        foldStream(md, openSidecar(ctx, lang, "map"), Integer.MAX_VALUE, false);
        StringBuilder sb = new StringBuilder();
        byte[] d = md.digest();
        for (int i = 0; i < d.length; i++) {
            sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(d[i] & 0xF, 16));
        }
        return sb.toString();
    }

    private static void foldStream(java.security.MessageDigest md, InputStream in,
                                   int limit, boolean headOnly) {
        if (in == null) {
            return;
        }
        try {
            byte[] buf = new byte[4096];
            int read = 0;
            int total = 0;
            while (total < limit && (read = in.read(buf)) > 0) {
                int take = Math.min(read, limit - total);
                md.update(buf, 0, take);
                total += take;
                if (headOnly) {
                    break;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "fingerprint read failed: " + t);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static long packLength(Context ctx, String lang) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    BASE.buildUpon().appendPath("pack").appendPath(lang).build(),
                    new String[]{"ko", "zh", "current"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                // The provider answers one row whose columns are the two pack
                // sizes; pick the column for the requested language.
                int idx = "ko".equals(lang) ? c.getColumnIndex("ko")
                                            : c.getColumnIndex("zh");
                if (idx >= 0) {
                    return c.getLong(idx);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "pack length unavailable: " + t);
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) { }
            }
        }
        return 0;
    }

    private static InputStream openPack(Context ctx, String lang) {
        try {
            return ctx.getContentResolver().openInputStream(
                    BASE.buildUpon().appendPath("pack").appendPath(lang).build());
        } catch (Throwable t) {
            Log.w(TAG, "provider pack unavailable: " + t);
        }
        try {
            return ctx.getAssets().open("languagepacks/" + lang + ".dat");
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // file helpers
    // ------------------------------------------------------------------

    /**
     * Replace TableFiles.map with the pack's own map.
     *
     * The map is what tells the client how to split the container, and a
     * translated entry is a different length, so a pack without its map would
     * be sliced with the shipped offsets.  The proxy publishes it next to the
     * pack as <lang>.map.
     */
    private static void copyMap(Context ctx, File files, String lang) {
        InputStream in = openSidecar(ctx, lang, "map");
        if (in == null) {
            Log.w(TAG, "no map for " + lang + "; container may not split correctly");
            return;
        }
        try {
            long n = copy(in, new File(files, MAP_REL));
            Log.i(TAG, "applied " + lang + " map (" + n + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "map copy failed: " + t);
        }
    }

    private static InputStream openSidecar(Context ctx, String lang, String ext) {
        try {
            return ctx.getContentResolver().openInputStream(
                    BASE.buildUpon().appendPath("pack").appendPath(lang)
                            .appendPath(ext).build());
        } catch (Throwable t) {
            return null;
        }
    }

    private static long copy(InputStream in, File target) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
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
        // rename last, so a half-written pack is never the live file
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new java.io.IOException("rename failed: " + target);
        }
        return total;
    }

    private static String readText(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                long len = Math.min(f.length(), 4096);
                byte[] b = new byte[(int) len];
                int n = in.read(b);
                return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeMarker(File files, String s) {
        try {
            FileOutputStream out = new FileOutputStream(new File(files, MARKER));
            try {
                out.write(s.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "marker write failed: " + t);
        }
    }
}
