package com.closersrt.server;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.File;

/**
 * Hand-off of the selected language and the language pack to the game process.
 *
 * WHY A PROVIDER INSTEAD OF A FILE PATH
 *   The pack lives in this app's external files dir.  On Android 11+ the FUSE
 *   layer refuses another app raw-path access to it, even with
 *   READ_EXTERNAL_STORAGE, so com.closersrt.savefix.LanguageFix cannot simply
 *   open /sdcard/Android/data/com.closersrt.server/files/langpacks/zh.dat.
 *   A provider is the supported way across the boundary: the game only needs
 *   ContentResolver.openInputStream(), which needs no permission and never
 *   touches Android/data.
 *
 *   The same provider serves the plain-text language code, so the game process
 *   has one dependency instead of two.
 *
 * URIs
 *   content://com.closersrt.proxy.lang/current      -> "ko" | "zh"
 *   content://com.closersrt.proxy.lang/pack/<lang>  -> the whole .dat
 */
public class LangProvider extends ContentProvider {

    public static final String AUTHORITY = "com.closersrt.proxy.lang";
    public static final Uri BASE = Uri.parse("content://" + AUTHORITY);

    private static final int CURRENT = 1;
    private static final int PACK = 2;
    private static final int PACK_SIDECAR = 3;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "current", CURRENT);
        MATCHER.addURI(AUTHORITY, "pack/*", PACK);
        MATCHER.addURI(AUTHORITY, "pack/*/*", PACK_SIDECAR);
    }

    @Override public boolean onCreate() {
        Language.init(getContext());
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (MATCHER.match(uri) == CURRENT) {
            return textDescriptor();
        }
        if (MATCHER.match(uri) == PACK_SIDECAR) {
            // content://.../pack/<lang>/<ext>  ->  langpacks/<lang>.<ext>
            java.util.List<String> seg = uri.getPathSegments();
            if (seg.size() != 3) {
                throw new FileNotFoundException("bad sidecar uri " + uri);
            }
            String code = seg.get(1);
            String ext = seg.get(2);
            if (!"map".equals(ext)) {
                throw new FileNotFoundException("unknown sidecar " + ext);
            }
            File f = Language.sidecarFile(code, ext);
            if (f == null || !f.isFile() || f.length() <= 0) {
                throw new FileNotFoundException("no " + ext + " for " + code);
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (MATCHER.match(uri) != PACK) {
            throw new FileNotFoundException("unknown uri " + uri);
        }
        String lang = uri.getLastPathSegment();
        File f = Language.packFile(lang);
        if (f == null || !f.isFile() || f.length() <= 0) {
            throw new FileNotFoundException("no pack for " + lang);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * A file descriptor for the language code.
     *
     * ParcelFileDescriptor cannot wrap a byte[] directly, and a pipe would need
     * a writer thread for one short string, so the value is mirrored into a
     * small cache file that both sides already own.
     */
    private ParcelFileDescriptor textDescriptor() throws FileNotFoundException {
        File f = new File(Language.stagingDir(), "lang.uri.txt");
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(Language.current().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            throw new FileNotFoundException("cannot materialise language: " + t);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return MATCHER.match(uri) == PACK ? "application/octet-stream" : "text/plain";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"current", "ko", "zh"});
        File ko = Language.packFile(Language.KO);
        File zh = Language.packFile(Language.ZH);
        c.addRow(new Object[]{Language.current(),
                ko != null && ko.isFile() ? ko.length() : 0,
                zh != null && zh.isFile() ? zh.length() : 0});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] args) { return 0; }
}
