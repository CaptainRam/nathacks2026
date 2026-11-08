package com.example.allergycheck.history;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/** The one-table database behind scan history. */
public final class ScanHistoryDatabase extends SQLiteOpenHelper {

    public static final String FILE_NAME = "scan_history.db";

    private static final int VERSION = 1;

    static final String TABLE = "scans";
    static final String ID = "_id";
    static final String CREATED_AT = "created_at";
    static final String OVERALL_STATUS = "overall_status";
    static final String SUMMARY_ALLERGENS = "summary_allergens";
    static final String LOW_CONFIDENCE = "low_confidence";
    static final String RAW_TEXT = "raw_text";
    static final String IMAGE_NAME = "image_name";
    static final String REPORT_BLOB = "report_blob";
    static final String REPORT_VERSION = "report_version";
    static final String MESSAGE_TITLE = "message_title";
    static final String MESSAGE_BODY = "message_body";

    public ScanHistoryDatabase(@NonNull Context context) {
        this(context, FILE_NAME);
    }

    /** Lets instrumented tests work on their own database file instead of the app's. */
    @VisibleForTesting
    ScanHistoryDatabase(@NonNull Context context, @Nullable String fileName) {
        super(context.getApplicationContext(), fileName, null, VERSION);
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        // overall_status and summary_allergens are denormalised out of the report on purpose: the
        // list query must never deserialise a blob to draw a row.
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + CREATED_AT + " INTEGER NOT NULL, "
                + OVERALL_STATUS + " TEXT NOT NULL, "
                + SUMMARY_ALLERGENS + " TEXT NOT NULL DEFAULT '', "
                + LOW_CONFIDENCE + " INTEGER NOT NULL DEFAULT 0, "
                + RAW_TEXT + " TEXT NOT NULL DEFAULT '', "
                + IMAGE_NAME + " TEXT, "
                + REPORT_BLOB + " BLOB, "
                + REPORT_VERSION + " INTEGER NOT NULL DEFAULT 0, "
                + MESSAGE_TITLE + " TEXT, "
                + MESSAGE_BODY + " TEXT)");
        db.execSQL("CREATE INDEX idx_scans_created_at ON " + TABLE
                + " (" + CREATED_AT + " DESC, " + ID + " DESC)");
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int from, int to) {
        // Nothing to migrate yet. History is a cache of the user's own scans, not a system of
        // record, so a future incompatible change may simply drop the table and start again.
    }
}
