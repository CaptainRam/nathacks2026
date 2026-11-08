package com.example.allergycheck.ingredients;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.example.allergycheck.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-wide, persistent download state. DownloadManager owns the transfer across process death. */
public final class LocalModelManager {
    // Public mirror of the original MediaPipe task; pinned revision and original file digest.
    // Official litert-community downloads require a Hugging Face login and license acceptance.
    public static final String MODEL_URL = "https://huggingface.co/nikhil2024/gemma3-1b-it-litert-mirror/resolve/"
            + "bad50bc8328170cf6f30336d33a4efe2d6f03eb4/gemma3-1b-it-int4.task";
    public static final long MODEL_BYTES = 554661243L;
    public static final String MODEL_SHA256 =
            "e3d981c01aeaaac69a84ffa0d4be13281b3176731063f1bea1c9fe6887bd9dee";
    private static final String PREFERENCES = "local_model";
    private static final String DOWNLOAD_ID = "download_id";
    private static final String LAST_ERROR = "last_error";
    private static final String INTRO_SHOWN = "intro_shown";
    @SuppressLint("StaticFieldLeak") // Only retains getApplicationContext(), never an Activity.
    private static LocalModelManager instance;

    public enum State { MISSING, DOWNLOADING, PAUSED, INSTALLING, READY, FAILED }

    public static final class Status {
        public final State state;
        public final long downloadedBytes;
        public final long totalBytes;
        public final String error;
        private final boolean installed;

        private Status(State state, long downloadedBytes, long totalBytes, String error) {
            this(state, downloadedBytes, totalBytes, error, state == State.READY);
        }

        private Status(State state, long downloadedBytes, long totalBytes, String error, boolean installed) {
            this.state = state;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.error = error;
            this.installed = installed;
        }

        public boolean isInstalled() {
            return installed;
        }

        public boolean isBusy() {
            return state == State.DOWNLOADING || state == State.PAUSED || state == State.INSTALLING;
        }
    }

    public interface Listener {
        void onModelStatusChanged(@NonNull Status status);
    }

    private final Context context;
    private final DownloadManager downloads;
    private final SharedPreferences preferences;
    private final ModelFiles files;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new HashSet<>();
    private Status status = new Status(State.MISSING, 0, MODEL_BYTES, null);
    private boolean installing;
    private final Runnable poll = this::refresh;

    @MainThread
    public static synchronized LocalModelManager get(@NonNull Context context) {
        if (instance == null) {
            instance = new LocalModelManager(context.getApplicationContext());
        }
        return instance;
    }

    private LocalModelManager(Context context) {
        this.context = context;
        downloads = context.getSystemService(DownloadManager.class);
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        files = new ModelFiles(modelDirectory(context));
        // Treat broadcasts as hints only: refresh queries our persisted ID and never trusts extras.
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    refresh();
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        refresh();
    }

    public static File modelDirectory(Context context) {
        File external = context.getExternalFilesDir(null);
        return new File(external == null ? context.getFilesDir() : external, "llm");
    }

    /** Also recognizes existing models installed manually with adb. Never returns a partial file. */
    public static File installedModel(Context context) {
        return new ModelFiles(modelDirectory(context)).findInstalled();
    }

    @MainThread
    public Status getStatus() {
        return status;
    }

    /** Shared by both launcher activities, so the introduction is offered once per installation. */
    @MainThread
    public boolean shouldShowIntroduction() {
        return !preferences.getBoolean(INTRO_SHOWN, false) && status.state == State.MISSING;
    }

    @MainThread
    public void markIntroductionShown() {
        preferences.edit().putBoolean(INTRO_SHOWN, true).apply();
    }

    @MainThread
    public void addListener(Listener listener) {
        refresh();
        listeners.add(listener);
        listener.onModelStatusChanged(status);
        schedulePoll();
    }

    @MainThread
    public void removeListener(Listener listener) {
        listeners.remove(listener);
        schedulePoll();
    }

    @MainThread
    public void download() {
        refresh();
        if (status.isInstalled() || status.isBusy()) {
            return;
        }
        try {
            if (downloads == null || context.getExternalFilesDir(null) == null) {
                throw new IOException("Download storage unavailable");
            }
            File staging = files.stagingFile();
            File directory = staging.getParentFile();
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())
                    || (staging.exists() && !staging.delete())) {
                throw new IOException("Cannot prepare download storage");
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(MODEL_URL))
                    .setTitle(context.getString(R.string.model_download_notification_title))
                    .setDescription(context.getString(R.string.model_download_notification_description))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, null, "llm/download/model.download");
            long id = downloads.enqueue(request);
            // Persist before returning to the screen, so reopening never enqueues a duplicate.
            if (!preferences.edit().putLong(DOWNLOAD_ID, id).remove(LAST_ERROR).commit()) {
                downloads.remove(id);
                throw new IOException("Cannot save download state");
            }
            update(new Status(State.DOWNLOADING, 0, MODEL_BYTES, null));
        } catch (IOException | RuntimeException error) {
            fail(context.getString(R.string.model_download_start_error));
        }
    }

    @MainThread
    public void delete() {
        // Verification publishes atomically on its worker. Do not race deletion with publication.
        if (installing) {
            return;
        }
        long id = preferences.getLong(DOWNLOAD_ID, -1);
        try {
            if (id != -1 && downloads != null) {
                downloads.remove(id);
            }
            preferences.edit().remove(DOWNLOAD_ID).remove(LAST_ERROR).apply();
            files.delete();
            update(new Status(State.MISSING, 0, MODEL_BYTES, null));
        } catch (IOException | RuntimeException error) {
            update(new Status(State.FAILED, 0, MODEL_BYTES,
                    context.getString(R.string.model_delete_error)));
        }
    }

    @MainThread
    public void refresh() {
        if (installing) {
            return;
        }
        long id = preferences.getLong(DOWNLOAD_ID, -1);
        if (id == -1) {
            File model = files.findInstalled();
            String error = preferences.getString(LAST_ERROR, null);
            update(new Status(model != null ? State.READY : error == null ? State.MISSING : State.FAILED,
                    model == null ? 0 : model.length(), model == null ? MODEL_BYTES : model.length(),
                    model == null ? error : null));
            return;
        }
        try (Cursor cursor = downloads == null ? null
                : downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                fail(context.getString(R.string.model_download_interrupted));
                return;
            }
            int downloadStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (total <= 0) {
                total = MODEL_BYTES;
            }
            if (downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                install(id);
            } else if (downloadStatus == DownloadManager.STATUS_FAILED) {
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                fail(context.getString(reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                        ? R.string.model_download_storage_error : R.string.model_download_failed));
            } else {
                update(new Status(downloadStatus == DownloadManager.STATUS_PAUSED ? State.PAUSED : State.DOWNLOADING,
                        Math.max(0, bytes), total, null));
            }
        } catch (RuntimeException error) {
            // Keep the ID: a temporary DownloadManager query error must not create a second job.
            update(new Status(State.PAUSED, status.downloadedBytes, status.totalBytes,
                    context.getString(R.string.model_download_query_error)));
        }
    }

    private void install(long id) {
        installing = true;
        update(new Status(State.INSTALLING, MODEL_BYTES, MODEL_BYTES, null));
        verifier.execute(() -> {
            boolean installed = false;
            try {
                files.install(MODEL_BYTES, MODEL_SHA256);
                installed = true;
            } catch (IOException | RuntimeException error) {
                installed = false;
            } finally {
                // In a finally block so that anything the catch does not cover — an Error such as
                // OutOfMemoryError while hashing — still clears the flag. While installing is true,
                // refresh(), delete() and download() all return early, so the Settings screen would
                // otherwise be stuck on "Installing" for the life of the process, with no retry.
                final boolean success = installed;
                handler.post(() -> finishInstall(id, success));
            }
        });
    }

    /**
     * Completes one verification attempt: posted exactly once per {@link #install(long)}, so the
     * flag is cleared and listeners are notified once, on the main thread, either way.
     */
    @MainThread
    private void finishInstall(long id, boolean success) {
        installing = false;
        if (!success) {
            fail(context.getString(R.string.model_download_verification_error));
            return;
        }
        preferences.edit().remove(DOWNLOAD_ID).remove(LAST_ERROR).apply();
        // The published model has a different path; removing the old job only removes staging.
        try {
            if (downloads != null) downloads.remove(id);
        } catch (RuntimeException ignored) {
            // A stale completed row does not affect the verified model.
        }
        refresh();
    }

    private void fail(String error) {
        long id = preferences.getLong(DOWNLOAD_ID, -1);
        if (id != -1 && downloads != null) {
            try {
                downloads.remove(id);
            } catch (RuntimeException ignored) {
                // Preserve the original error and offer retry even if the system service is unavailable.
            }
        }
        preferences.edit().remove(DOWNLOAD_ID).putString(LAST_ERROR, error).apply();
        update(new Status(State.FAILED, 0, MODEL_BYTES, error));
    }

    private void update(Status next) {
        status = new Status(next.state, next.downloadedBytes, next.totalBytes, next.error,
                files.findInstalled() != null);
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onModelStatusChanged(status);
        }
        schedulePoll();
    }

    private void schedulePoll() {
        handler.removeCallbacks(poll);
        if (!listeners.isEmpty() && (status.state == State.DOWNLOADING || status.state == State.PAUSED)) {
            handler.postDelayed(poll, 1000);
        }
    }
}
