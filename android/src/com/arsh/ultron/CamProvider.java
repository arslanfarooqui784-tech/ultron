package com.arsh.ultron;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Lets the camera app save the one photo ULTRON asked for ("what is this?"). */
public class CamProvider extends ContentProvider {
    public static final String AUTHORITY = "com.arsh.ultron.cam";

    public static File file(android.content.Context c) {
        File d = new File(c.getCacheDir(), "cam");
        d.mkdirs();
        return new File(d, "look.jpg");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"look.jpg".equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.parseMode(mode.contains("w") ? "rwt" : "r"));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
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
