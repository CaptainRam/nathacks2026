package com.example.allergycheck.history;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.example.allergycheck.analysis.AnalysisReport;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scan history, from the main thread. Every database and file operation runs on one worker, and
 * every callback comes back on the main thread.
 *
 * <p>The worker is deliberately single-threaded rather than a pool: it serialises saves, deletes
 * and the housekeeping pass against each other, which is what lets "delete the image when its row
 * goes" be correct without any locking.
 *
 * <p>Application-scoped, like {@code LocalModelManager}, for two reasons: a save must not be
 * cancelled because the user rotated the device while it was in flight, and the scan page (which
 * writes) and the history page (which reads) need the same instance.
 */
public final class ScanHistory {

    /** How many rows the list asks for. Far above what anyone accumulates inside the window. */
    public static final int DEFAULT_LIMIT = 200;

    private static final String TAG = "ScanHistory";
    private static final String IMAGE_DIRECTORY = "history";

    @SuppressLint("StaticFieldLeak") // Holds the application context only; lives as long as it.
    private static ScanHistory instance;

    public interface ItemsCallback {
        @MainThread
        void onItems(@NonNull List<ScanHistoryItem> items);
    }

    public interface DetailCallback {
        @MainThread
        void onDetail(@Nullable ScanHistoryDetail detail);
    }

    public interface DoneCallback {
        @MainThread
        void onDone();
    }

    private final Context context;
    private final ScanHistoryStore store;
    private final File imageDirectory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @MainThread
    public static synchronized ScanHistory get(@NonNull Context context) {
        if (instance == null) {
            Context application = context.getApplicationContext();
            File directory = new File(application.getFilesDir(), IMAGE_DIRECTORY);
            instance = new ScanHistory(application,
                    new ScanHistoryStore(new ScanHistoryDatabase(application), directory),
                    directory);
        }
        return instance;
    }

    @VisibleForTesting
    ScanHistory(@NonNull Context context, @NonNull ScanHistoryStore store,
                @NonNull File imageDirectory) {
        this.context = context;
        this.store = store;
        this.imageDirectory = imageDirectory;
    }

    // ----- Writing -----

    /**
     * Records a finished analysis, with a display-sized copy of the photo. Fire and forget: the
     * user already has their result, so nothing here is allowed to block or to surface an error.
     */
    @MainThread
    public void save(@NonNull AnalysisReport report, @Nullable Uri photo) {
        long createdAt = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                saveImage(store.insert(createdAt, report), photo);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not save a scan to history", e);
            }
        });
    }

    /** Records a scan that reached the user as a message rather than a report. */
    @MainThread
    public void saveFailure(@NonNull String rawText, @NonNull String title, @NonNull String body,
                            @Nullable Uri photo) {
        long createdAt = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                saveImage(store.insertFailure(createdAt, rawText, title, body), photo);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not save a failed scan to history", e);
            }
        });
    }

    /**
     * Writes the row's image after the row exists. That order matters: a crash can then only
     * strand a file, never leave a row pointing at an image that is not there. A row whose image
     * is missing still draws, with a placeholder.
     */
    private void saveImage(long id, @Nullable Uri photo) {
        if (id < 0 || photo == null) {
            return;
        }
        String name = ScanHistoryImages.fileNameFor(id);
        File target = new File(imageDirectory, name);
        try {
            ScanHistoryImages.write(context, photo, target);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "Could not save the photo for scan " + id, e);
            ScanHistoryImages.delete(imageDirectory, name);
            return;
        }
        store.attachImage(id, name);
    }

    // ----- Reading -----

    @MainThread
    public void load(int limit, @NonNull ItemsCallback callback) {
        worker.execute(() -> {
            List<ScanHistoryItem> items;
            try {
                items = store.recent(limit);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not read history", e);
                items = Collections.emptyList();
            }
            List<ScanHistoryItem> result = items;
            main.post(() -> callback.onItems(result));
        });
    }

    @MainThread
    public void detail(long id, @NonNull DetailCallback callback) {
        worker.execute(() -> {
            ScanHistoryDetail detail;
            try {
                detail = store.detail(id);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not read scan " + id, e);
                detail = null;
            }
            ScanHistoryDetail result = detail;
            main.post(() -> callback.onDetail(result));
        });
    }

    /** Where a row's saved photo lives, for decoding a thumbnail or the detail image. */
    @NonNull
    public File imageFile(@NonNull String imageName) {
        return new File(imageDirectory, imageName);
    }

    // ----- Deleting -----

    @MainThread
    public void delete(long id, @Nullable DoneCallback callback) {
        worker.execute(() -> {
            try {
                String name = store.delete(id);
                ScanHistoryImages.delete(imageDirectory, name);
                // Also by the id-derived name, in case the row never attached one.
                ScanHistoryImages.delete(imageDirectory, ScanHistoryImages.fileNameFor(id));
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not delete scan " + id, e);
            }
            finish(callback);
        });
    }

    @MainThread
    public void clear(@Nullable DoneCallback callback) {
        worker.execute(() -> {
            try {
                for (String name : store.clear()) {
                    ScanHistoryImages.delete(imageDirectory, name);
                }
                // The table is empty now, so nothing left in here can belong to a row.
                deleteOrphans(Collections.emptySet());
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not clear history", e);
            }
            finish(callback);
        });
    }

    /**
     * Housekeeping: prunes anything past the retention window, then removes images with no row.
     * Pruning goes first so the files it frees are gone before the orphan pass lists the directory.
     *
     * <p>Called once at startup and again whenever the history page is opened. Startup alone would
     * be wrong for a session left running past a row's expiry — the list would still show scans
     * the policy says are gone.
     */
    @MainThread
    public void maintain(@Nullable DoneCallback callback) {
        long now = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                for (String name : store.deleteOlderThan(ScanRetention.cutoff(now))) {
                    ScanHistoryImages.delete(imageDirectory, name);
                }
                deleteOrphans(store.liveIds());
            } catch (RuntimeException e) {
                Log.w(TAG, "History housekeeping failed", e);
            }
            finish(callback);
        });
    }

    private void deleteOrphans(@NonNull Set<Long> liveIds) {
        String[] names = imageDirectory.list();
        if (names == null) {
            return;
        }
        for (String orphan : ScanHistoryImages.orphanNames(Arrays.asList(names), liveIds)) {
            ScanHistoryImages.delete(imageDirectory, orphan);
        }
    }

    private void finish(@Nullable DoneCallback callback) {
        if (callback != null) {
            main.post(callback::onDone);
        }
    }
}
