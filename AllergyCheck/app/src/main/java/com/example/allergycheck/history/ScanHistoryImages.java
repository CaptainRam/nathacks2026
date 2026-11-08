package com.example.allergycheck.history;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The saved label photos behind scan history: where they live, how big they are, and which files
 * on disk no longer belong to a row.
 *
 * <p>File names are derived from the row id, which makes orphan detection a set difference and
 * makes it impossible for two rows to claim the same file.
 *
 * <p>Note this is a <em>display</em> copy, bounded at {@link #LONG_EDGE_PX}, and so is much smaller
 * than what {@code ocr.LabelImageDecoder} produces: that normalises into a 1600–2600 px band and
 * even upscales tight crops, because small print is lost to the recogniser below that. Here the
 * image is only ever drawn on screen, so those extra pixels would be stored for nothing.
 */
public final class ScanHistoryImages {

    /**
     * The widest a label photo is ever drawn: {@code photo_preview} is 320dp tall inside a content
     * column capped at 520dp, so 320dp is the long edge on a phone. At xxhdpi that is 960 px, so
     * this leaves a little headroom and matches the physical width of most Android phones.
     */
    public static final int LONG_EDGE_PX = 1080;

    /** Visually transparent for photographic JPEG; lands a label photo at roughly 120–250 KB. */
    public static final int JPEG_QUALITY = 80;

    private static final String TAG = "ScanHistoryImages";
    private static final String PREFIX = "scan_";
    private static final String SUFFIX = ".jpg";

    private ScanHistoryImages() {
    }

    @NonNull
    public static String fileNameFor(long rowId) {
        return PREFIX + rowId + SUFFIX;
    }

    /**
     * The row id a file name belongs to, or {@code -1} when the name is not one of ours. Anything
     * unrecognised is left alone rather than guessed at — see {@link #orphanNames}.
     */
    public static long idFromFileName(@NonNull String name) {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return -1;
        }
        String digits = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        if (digits.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            // Longer than a long; certainly not an id we ever wrote.
            return -1;
        }
    }

    /**
     * The files in the history directory that no row points at. Pure, because getting this wrong
     * deletes a user's data: a name we cannot parse is never reported as an orphan.
     */
    @NonNull
    public static List<String> orphanNames(@Nullable Collection<String> directoryNames,
                                           @NonNull Set<Long> liveIds) {
        List<String> orphans = new ArrayList<>();
        if (directoryNames == null) {
            return orphans;
        }
        for (String name : directoryNames) {
            if (name == null) {
                continue;
            }
            long id = idFromFileName(name);
            if (id >= 0 && !liveIds.contains(id)) {
                orphans.add(name);
            }
        }
        return orphans;
    }

    /**
     * Decodes {@code photo} bounded to {@link #LONG_EDGE_PX} and writes it to {@code target} as
     * JPEG. On failure the partial file is removed, so a half-written image never survives.
     */
    @WorkerThread
    public static void write(@NonNull Context context, @NonNull Uri photo, @NonNull File target)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        Bitmap bitmap = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(context.getContentResolver(), photo),
                (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (longest > LONG_EDGE_PX) {
                        float scale = (float) LONG_EDGE_PX / longest;
                        decoder.setTargetSize(
                                Math.max(1, Math.round(info.getSize().getWidth() * scale)),
                                Math.max(1, Math.round(info.getSize().getHeight() * scale)));
                    }
                });
        try (OutputStream out = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw new IOException("could not compress " + photo);
            }
        } catch (IOException | RuntimeException e) {
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Could not remove partial " + target);
            }
            throw e;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * Removes one saved image. Resolves the name against {@code directory} and checks the result is
     * still inside it, so a stored name can never reach outside the history directory.
     */
    @WorkerThread
    public static void delete(@NonNull File directory, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        File file = new File(directory, name);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
                Log.w(TAG, "Refusing to delete outside the history directory: " + name);
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not resolve " + name, e);
            return;
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete " + file);
        }
    }
}
