package com.nous.lipread;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class CaptureFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.nous.lipread.fileprovider";

    public static Uri uriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("capture")
                .appendPath(file.getName())
                .build();
    }

    public static File captureDirectory(android.content.Context context) {
        return new File(context.getCacheDir(), "captures");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        requireCaptureUri(uri);
        return "video/mp4";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(file.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(file.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        int flags;
        if ("r".equals(mode)) flags = ParcelFileDescriptor.MODE_READ_ONLY;
        else if ("w".equals(mode) || "wt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("rw".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        } else {
            throw new FileNotFoundException("Unsupported file mode");
        }
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new FileNotFoundException("Capture directory is unavailable");
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return resolve(uri).delete() ? 1 : 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert is not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update is not supported");
    }

    private File resolve(Uri uri) {
        requireCaptureUri(uri);
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new SecurityException("Invalid capture path");
        }
        File directory = captureDirectory(getContext());
        File file = new File(directory, name);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
                throw new SecurityException("Capture path escapes app storage");
            }
        } catch (IOException e) {
            throw new SecurityException("Invalid capture path", e);
        }
        return file;
    }

    private static void requireCaptureUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 2
                || !"capture".equals(uri.getPathSegments().get(0))) {
            throw new SecurityException("Invalid capture URI");
        }
    }
}
