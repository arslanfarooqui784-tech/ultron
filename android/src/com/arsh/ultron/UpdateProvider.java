package com.arsh.ultron;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Hands the downloaded update file to Android's installer (read-only, only that one file). */
public class UpdateProvider extends ContentProvider {
    public static final String AUTHORITY = "com.arsh.ultron.update";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"ULTRON-update.apk".equals(uri.getLastPathSegment()) || !"r".equals(mode)) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), "update"), "ULTRON-update.apk");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
