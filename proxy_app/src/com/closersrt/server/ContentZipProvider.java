package com.closersrt.server;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;

/**
 * Read-only handoff of the content ZIP selected in the proxy.
 *
 * The game's importer opens content://com.closersrt.proxy.contentzip/zip and
 * gets the exact ZIP the operator picked through SAF.  No storage permission
 * and no Android/data access are involved.
 */
public class ContentZipProvider extends ContentProvider {

    public static final String AUTHORITY = "com.closersrt.proxy.contentzip";
    public static final Uri ZIP_URI = Uri.parse("content://" + AUTHORITY + "/zip");
    private static final int ZIP = 1;
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static { MATCHER.addURI(AUTHORITY, "zip", ZIP); }

    @Override public boolean onCreate() {
        ContentZip.init(getContext());
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (MATCHER.match(uri) != ZIP) throw new FileNotFoundException("unknown uri");
        ContentZip z = ContentZip.get();
        if (z == null || !z.isHandoff()) throw new FileNotFoundException("no content zip selected");
        Uri zip = z.zipUri();
        try {
            return getContext().getContentResolver().openFileDescriptor(zip, "r");
        } catch (Throwable t) {
            throw new FileNotFoundException("cannot open zip: " + t);
        }
    }

    @Override public String getType(Uri uri) { return "application/zip"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        ContentZip z = ContentZip.get();
        MatrixCursor c = new MatrixCursor(new String[]{"name", "entries"});
        c.addRow(new Object[]{z == null ? "" : z.displayName(), z == null ? 0 : z.count()});
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
