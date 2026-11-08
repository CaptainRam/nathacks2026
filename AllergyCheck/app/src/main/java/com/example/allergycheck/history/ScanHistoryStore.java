package com.example.allergycheck.history;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes scan history. Every method blocks on SQLite, so all of them are
 * {@link WorkerThread}; {@link ScanHistory} is the main-thread door onto this.
 *
 * <p>The report is stored as a Java-serialised blob, which is not a free choice: every constructor
 * in the analysis package is package-private so that only {@code Analyzer} can build a report, and
 * {@code AnalysisResultView} needs a real {@code AnalysisReport} to render one. Deserialisation
 * rebuilds the graph without calling a constructor, so it is the one route that leaves that
 * invariant alone. The columns a list row needs are denormalised alongside the blob, and the OCR
 * text gets its own column, so a blob this build cannot read degrades to a summary rather than an
 * error.
 */
public final class ScanHistoryStore {

    /**
     * The {@code serialVersionUID} of {@link AnalysisReport} at the time of writing. A row stamped
     * with anything else is not deserialised at all, so bumping that id degrades old rows to
     * summary-only instead of throwing on every read.
     */
    static final int CURRENT_REPORT_VERSION = 2;

    private static final String TAG = "ScanHistoryStore";

    /** Unit separator: it cannot occur in an allergen label, so it needs no escaping. */
    private static final String LABEL_SEPARATOR = String.valueOf((char) 0x1F);

    private static final String[] LIST_COLUMNS = {
            ScanHistoryDatabase.ID,
            ScanHistoryDatabase.CREATED_AT,
            ScanHistoryDatabase.OVERALL_STATUS,
            ScanHistoryDatabase.SUMMARY_ALLERGENS,
            ScanHistoryDatabase.LOW_CONFIDENCE,
            ScanHistoryDatabase.IMAGE_NAME,
            ScanHistoryDatabase.MESSAGE_TITLE,
    };

    private static final String[] DETAIL_COLUMNS = {
            ScanHistoryDatabase.ID,
            ScanHistoryDatabase.CREATED_AT,
            ScanHistoryDatabase.OVERALL_STATUS,
            ScanHistoryDatabase.SUMMARY_ALLERGENS,
            ScanHistoryDatabase.LOW_CONFIDENCE,
            ScanHistoryDatabase.IMAGE_NAME,
            ScanHistoryDatabase.MESSAGE_TITLE,
            ScanHistoryDatabase.RAW_TEXT,
            ScanHistoryDatabase.MESSAGE_BODY,
            ScanHistoryDatabase.REPORT_BLOB,
            ScanHistoryDatabase.REPORT_VERSION,
    };

    private static final String NEWEST_FIRST =
            ScanHistoryDatabase.CREATED_AT + " DESC, " + ScanHistoryDatabase.ID + " DESC";

    private final ScanHistoryDatabase database;
    private final File imageDirectory;

    public ScanHistoryStore(@NonNull ScanHistoryDatabase database, @NonNull File imageDirectory) {
        this.database = database;
        this.imageDirectory = imageDirectory;
    }

    @NonNull
    File imageDirectory() {
        return imageDirectory;
    }

    // ----- Writing -----

    /** Stores a finished analysis. {@code createdAt} is passed in so tests can date rows. */
    @WorkerThread
    public long insert(long createdAt, @NonNull AnalysisReport report) {
        ContentValues values = new ContentValues();
        values.put(ScanHistoryDatabase.CREATED_AT, createdAt);
        values.put(ScanHistoryDatabase.OVERALL_STATUS, report.overall().name());
        values.put(ScanHistoryDatabase.SUMMARY_ALLERGENS, joinLabels(report));
        values.put(ScanHistoryDatabase.LOW_CONFIDENCE, report.lowConfidence() ? 1 : 0);
        values.put(ScanHistoryDatabase.RAW_TEXT, report.label().rawText());
        byte[] blob = serialise(report);
        if (blob != null) {
            values.put(ScanHistoryDatabase.REPORT_BLOB, blob);
            values.put(ScanHistoryDatabase.REPORT_VERSION, CURRENT_REPORT_VERSION);
        }
        return database.getWritableDatabase().insert(ScanHistoryDatabase.TABLE, null, values);
    }

    /**
     * Stores a scan that reached the user without a report - no text found, no ingredients found,
     * or a recognition error. The title and body are the ones that were shown, so the detail page
     * can reproduce that screen exactly.
     */
    @WorkerThread
    public long insertFailure(long createdAt, @NonNull String rawText, @NonNull String title,
                              @NonNull String body) {
        ContentValues values = new ContentValues();
        values.put(ScanHistoryDatabase.CREATED_AT, createdAt);
        // Unreadable rather than "not detected": these scans say nothing about the label.
        values.put(ScanHistoryDatabase.OVERALL_STATUS, AnalysisReport.Status.UNREADABLE.name());
        values.put(ScanHistoryDatabase.SUMMARY_ALLERGENS, "");
        values.put(ScanHistoryDatabase.LOW_CONFIDENCE, 0);
        values.put(ScanHistoryDatabase.RAW_TEXT, rawText);
        values.put(ScanHistoryDatabase.MESSAGE_TITLE, title);
        values.put(ScanHistoryDatabase.MESSAGE_BODY, body);
        return database.getWritableDatabase().insert(ScanHistoryDatabase.TABLE, null, values);
    }

    @WorkerThread
    public void attachImage(long id, @NonNull String imageName) {
        ContentValues values = new ContentValues();
        values.put(ScanHistoryDatabase.IMAGE_NAME, imageName);
        database.getWritableDatabase().update(ScanHistoryDatabase.TABLE, values,
                ScanHistoryDatabase.ID + " = ?", new String[]{Long.toString(id)});
    }

    // ----- Reading -----

    /** Newest first. Deliberately selects neither the report blob nor the OCR text. */
    @NonNull
    @WorkerThread
    public List<ScanHistoryItem> recent(int limit) {
        List<ScanHistoryItem> items = new ArrayList<>();
        try (Cursor cursor = database.getReadableDatabase().query(ScanHistoryDatabase.TABLE,
                LIST_COLUMNS, null, null, null, null, NEWEST_FIRST, Integer.toString(limit))) {
            while (cursor.moveToNext()) {
                items.add(readItem(cursor));
            }
        }
        return items;
    }

    @WorkerThread
    public int count() {
        try (Cursor cursor = database.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + ScanHistoryDatabase.TABLE, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    @Nullable
    @WorkerThread
    public ScanHistoryDetail detail(long id) {
        try (Cursor cursor = database.getReadableDatabase().query(ScanHistoryDatabase.TABLE,
                DETAIL_COLUMNS, ScanHistoryDatabase.ID + " = ?",
                new String[]{Long.toString(id)}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            ScanHistoryItem item = readItem(cursor);
            String rawText = cursor.isNull(7) ? "" : cursor.getString(7);
            String messageBody = cursor.isNull(8) ? null : cursor.getString(8);
            AnalysisReport report = null;
            if (!cursor.isNull(9) && cursor.getInt(10) == CURRENT_REPORT_VERSION) {
                report = deserialise(cursor.getBlob(9));
            }
            return new ScanHistoryDetail(item, report, rawText, messageBody);
        }
    }

    @NonNull
    @WorkerThread
    public Set<Long> liveIds() {
        Set<Long> ids = new HashSet<>();
        try (Cursor cursor = database.getReadableDatabase().query(ScanHistoryDatabase.TABLE,
                new String[]{ScanHistoryDatabase.ID}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        return ids;
    }

    /** The first seven columns of both projections are the same, on purpose. */
    @NonNull
    private static ScanHistoryItem readItem(@NonNull Cursor cursor) {
        return new ScanHistoryItem(
                cursor.getLong(0),
                cursor.getLong(1),
                readStatus(cursor.getString(2)),
                splitLabels(cursor.getString(3)),
                cursor.getInt(4) != 0,
                cursor.isNull(5) ? null : cursor.getString(5),
                cursor.isNull(6) ? null : cursor.getString(6));
    }

    // ----- Deleting -----

    /**
     * Removes one row.
     *
     * @return its stored image name, or {@code null} if it had none or the row was already gone
     */
    @Nullable
    @WorkerThread
    public String delete(long id) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            String imageName = null;
            try (Cursor cursor = db.query(ScanHistoryDatabase.TABLE,
                    new String[]{ScanHistoryDatabase.IMAGE_NAME},
                    ScanHistoryDatabase.ID + " = ?", new String[]{Long.toString(id)},
                    null, null, null)) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    imageName = cursor.getString(0);
                }
            }
            db.delete(ScanHistoryDatabase.TABLE, ScanHistoryDatabase.ID + " = ?",
                    new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            return imageName;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Empties the table.
     *
     * @return every image name it held
     */
    @NonNull
    @WorkerThread
    public List<String> clear() {
        return deleteWhere(null, null);
    }

    /**
     * Removes rows created before {@code cutoff}.
     *
     * @return their image names
     */
    @NonNull
    @WorkerThread
    public List<String> deleteOlderThan(long cutoff) {
        return deleteWhere(ScanHistoryDatabase.CREATED_AT + " < ?",
                new String[]{Long.toString(cutoff)});
    }

    @NonNull
    private List<String> deleteWhere(@Nullable String where, @Nullable String[] args) {
        SQLiteDatabase db = database.getWritableDatabase();
        db.beginTransaction();
        try {
            List<String> names = new ArrayList<>();
            try (Cursor cursor = db.query(ScanHistoryDatabase.TABLE,
                    new String[]{ScanHistoryDatabase.ID, ScanHistoryDatabase.IMAGE_NAME},
                    where, args, null, null, null)) {
                while (cursor.moveToNext()) {
                    // The id-derived name goes on the list too, so a file stranded by a save that
                    // crashed between writing the image and attaching it is still cleaned up.
                    names.add(ScanHistoryImages.fileNameFor(cursor.getLong(0)));
                    if (!cursor.isNull(1)) {
                        names.add(cursor.getString(1));
                    }
                }
            }
            db.delete(ScanHistoryDatabase.TABLE, where, args);
            db.setTransactionSuccessful();
            return names;
        } finally {
            db.endTransaction();
        }
    }

    // ----- Serialisation -----

    @Nullable
    private static byte[] serialise(@NonNull AnalysisReport report) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(report);
        } catch (IOException | RuntimeException e) {
            // Better a summary-only row than no row: the verdict the user saw is already in the
            // denormalised columns.
            Log.w(TAG, "Could not serialise the report; storing the summary only", e);
            return null;
        }
        return bytes.toByteArray();
    }

    @Nullable
    private static AnalysisReport deserialise(@NonNull byte[] blob) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob))) {
            Object read = in.readObject();
            return read instanceof AnalysisReport ? (AnalysisReport) read : null;
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            // A row written by an incompatible build, or a corrupted blob. The caller shows the
            // summary and the stored OCR text instead.
            Log.w(TAG, "Could not read a stored report; showing the summary only", e);
            return null;
        }
    }

    // ----- Columns -----

    /** The labels that carried the overall status, in report order. */
    @NonNull
    private static String joinLabels(@NonNull AnalysisReport report) {
        StringBuilder out = new StringBuilder();
        for (AllergenResult result : report.results()) {
            if (result.status() == report.overall()) {
                if (out.length() > 0) {
                    out.append(LABEL_SEPARATOR);
                }
                out.append(result.allergen().label());
            }
        }
        return out.toString();
    }

    @NonNull
    private static List<String> splitLabels(@Nullable String joined) {
        if (joined == null || joined.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(Arrays.asList(joined.split(LABEL_SEPARATOR, -1)));
    }

    /**
     * Statuses are stored by name, never by ordinal: the enum is declared in severity order, so
     * reordering it must not silently reinterpret rows already on disk.
     */
    @NonNull
    private static AnalysisReport.Status readStatus(@Nullable String name) {
        if (name != null) {
            try {
                return AnalysisReport.Status.valueOf(name);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Unknown stored status " + name, e);
            }
        }
        // Never fall back to a status that reads as "clear".
        return AnalysisReport.Status.UNREADABLE;
    }
}
