package me.d3sox.nevumi;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shares the fixture WAV under the name in its link, with only a name and a size, like a file manager's own provider. It runs in the
 * test package's process, which has no Kotlin standard library, so it is written in Java.
 */
public class SharedSongProvider extends ContentProvider {
    private File song() throws IOException {
        File file = new File(getContext().getCacheDir(), "shared-song.wav");
        try (InputStream input = getContext().getAssets().open("fixture.wav"); OutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) output.write(buffer, 0, read);
        }
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        try { cursor.addRow(new Object[] { uri.getLastPathSegment(), song().length() }); }
        catch (IOException error) { throw new IllegalStateException(error); }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try { return ParcelFileDescriptor.open(song(), ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }

    @Override public String getType(Uri uri) { return "audio/wav"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
