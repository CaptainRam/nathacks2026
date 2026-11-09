package com.example.allergycheck.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * The persistence layer, exercised on a device because {@code SQLiteOpenHelper} is only a stub on
 * the JVM test classpath. Each test gets its own database file.
 */
@RunWith(AndroidJUnit4.class)
public class ScanHistoryStoreTest {

    private static final long NOW = 1_762_600_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private Context context;
    private String fileName;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private File imageDirectory;
    private AllergenDictionary dictionary;

    @Before
    public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        fileName = "test_history_" + UUID.randomUUID() + ".db";
        database = new ScanHistoryDatabase(context, fileName);
        imageDirectory = new File(context.getCacheDir(), "history_test_" + UUID.randomUUID());
        assertTrue(imageDirectory.mkdirs());
        store = new ScanHistoryStore(database, imageDirectory);
        dictionary = shippedDictionary();
    }

    @After
    public void tearDown() {
        database.close();
        context.deleteDatabase(fileName);
        File[] files = imageDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                assertTrue(file.delete());
            }
        }
        imageDirectory.delete();
    }

    // ----- Reports -----

    @Test
    public void insertedReportComesBackInTheListWithItsSummaryColumns() {
        AnalysisReport report = analyse("Ingredients: wheat flour, milk powder, sugar.");
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());

        long id = store.insert(NOW, report);
        assertTrue(id > 0);

        List<ScanHistoryItem> items = store.recent(10);
        assertEquals(1, items.size());
        ScanHistoryItem item = items.get(0);
        assertEquals(id, item.id());
        assertEquals(NOW, item.createdAt());
        assertEquals(AnalysisReport.Status.CONTAINS, item.status());
        assertFalse("the summary must name the allergens", item.allergenLabels().isEmpty());
        assertNull("a report row carries no message", item.messageTitle());
        assertFalse(item.isFailure());
        assertFalse(item.aiIncomplete());
    }

    /**
     * The assertion that matters most here: it pins that the whole analysis object graph stays
     * serialisable, which is what the blob column depends on.
     */
    @Test
    public void aStoredReportRoundTripsThroughTheBlob() {
        AnalysisReport original = analyse(
                "Ingredients: sugar, chocolate (cocoa butter, soy lecithin), milk.\n"
                        + "May contain peanuts.");
        long id = store.insert(NOW, original);

        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        AnalysisReport restored = detail.report();
        assertNotNull("the report should have been rebuilt", restored);

        assertEquals(original.overall(), restored.overall());
        assertEquals(original.lowConfidence(), restored.lowConfidence());
        assertEquals(original.results().size(), restored.results().size());
        for (int i = 0; i < original.results().size(); i++) {
            assertEquals(original.results().get(i).allergen().id(),
                    restored.results().get(i).allergen().id());
            assertEquals(original.results().get(i).status(),
                    restored.results().get(i).status());
            assertEquals(original.results().get(i).findings().size(),
                    restored.results().get(i).findings().size());
        }
        assertEquals(original.label().rawText(), restored.label().rawText());
        assertEquals(original.label().ingredients().size(), restored.label().ingredients().size());
        assertEquals(original.label().statements().size(), restored.label().statements().size());
        assertEquals(original.label().ingredientHeadingFound(),
                restored.label().ingredientHeadingFound());
    }

    @Test
    public void nestedIngredientsAndStatementsSurvive() {
        AnalysisReport original = analyse(
                "Ingredients: chocolate (sugar, cocoa butter, soy lecithin), wheat flour.\n"
                        + "Contains: soy, wheat.\nMay contain traces of tree nuts.");
        ScanHistoryDetail detail = store.detail(store.insert(NOW, original));
        assertNotNull(detail);
        assertNotNull(detail.report());
        // Sub-ingredients are stored flat with a depth, so check a depth-1 item made it through.
        boolean nested = false;
        for (int i = 0; i < detail.report().label().ingredients().size(); i++) {
            if (detail.report().label().ingredients().get(i).depth() > 0) {
                nested = true;
                break;
            }
        }
        assertTrue("a sub-ingredient should have round-tripped", nested);
        assertFalse(detail.report().label().statements().isEmpty());
    }

    @Test
    public void rawTextIsStoredInItsOwnColumn() {
        String text = "Ingredients: milk, sugar.";
        ScanHistoryDetail detail = store.detail(store.insert(NOW, analyse(text)));
        assertNotNull(detail);
        assertEquals(text, detail.rawText());
    }

    @Test
    public void incompleteAiReportKeepsKnownHitsAndItsCaveatInListAndDetail() {
        AnalysisReport original = AiAnalyzer.incompleteReport(
                analyse("Ingredients: milk powder, sugar, water."));
        assertEquals(AnalysisReport.Status.CONTAINS, original.overall());
        assertTrue(original.aiIncomplete());
        long id = store.insert(NOW, original);

        ScanHistoryItem item = store.recent(1).get(0);
        assertTrue(item.aiIncomplete());
        assertEquals(AnalysisReport.Status.CONTAINS, item.status());
        assertTrue(item.allergenLabels().contains("Milk"));
        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertTrue(detail.item().aiIncomplete());
        assertNotNull(detail.report());
        assertTrue(detail.report().aiAnalysis());
        assertTrue(detail.report().aiIncomplete());
        assertEquals(original.results().get(0).findings().size(),
                detail.report().results().get(0).findings().size());
    }

    @Test
    public void incompleteAiCaveatDoesNotDependOnTheReportBlob() {
        AnalysisReport original = AiAnalyzer.incompleteReport(
                analyse("Ingredients: water, sugar, salt."));
        assertEquals(AnalysisReport.Status.UNREADABLE, original.overall());
        long id = store.insert(NOW, original);
        ContentValues values = new ContentValues();
        values.putNull("report_blob");
        database.getWritableDatabase().update("scans", values, "_id = ?",
                new String[]{Long.toString(id)});

        assertTrue(store.recent(1).get(0).aiIncomplete());
        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertNull(detail.report());
        assertTrue(detail.item().aiIncomplete());
        assertEquals(original.label().rawText(), detail.rawText());
    }

    @Test
    public void versionOneUpgradePreservesReportsMessagesAndPhotoNames() throws IOException {
        database.close();
        AnalysisReport original = analyse("Ingredients: milk powder, sugar, water.");
        byte[] blob;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
            out.flush();
            blob = bytes.toByteArray();
        }
        long reportId;
        long failureId;
        try (SQLiteDatabase legacy = context.openOrCreateDatabase(
                fileName, Context.MODE_PRIVATE, null)) {
            // The exact version 1 schema, without the new summary flag.
            legacy.execSQL("CREATE TABLE scans (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "created_at INTEGER NOT NULL, overall_status TEXT NOT NULL, "
                    + "summary_allergens TEXT NOT NULL DEFAULT '', "
                    + "low_confidence INTEGER NOT NULL DEFAULT 0, "
                    + "raw_text TEXT NOT NULL DEFAULT '', image_name TEXT, report_blob BLOB, "
                    + "report_version INTEGER NOT NULL DEFAULT 0, message_title TEXT, "
                    + "message_body TEXT)");
            legacy.execSQL("CREATE INDEX idx_scans_created_at ON scans (created_at DESC, _id DESC)");
            ContentValues reportValues = new ContentValues();
            reportValues.put("created_at", NOW);
            reportValues.put("overall_status", original.overall().name());
            reportValues.put("summary_allergens", "Milk");
            reportValues.put("raw_text", original.label().rawText());
            reportValues.put("image_name", "scan_saved.jpg");
            reportValues.put("report_blob", blob);
            reportValues.put("report_version", 2);
            reportId = legacy.insertOrThrow("scans", null, reportValues);
            ContentValues failureValues = new ContentValues();
            failureValues.put("created_at", NOW + 1);
            failureValues.put("overall_status", "UNREADABLE");
            failureValues.put("raw_text", "blurred label");
            failureValues.put("message_title", "Ingredient extraction");
            failureValues.put("message_body", "No ingredients found.");
            failureId = legacy.insertOrThrow("scans", null, failureValues);
            legacy.setVersion(1);
        }

        // Opening through the current helper must perform the additive migration.
        assertEquals(3, database.getReadableDatabase().getVersion());
        assertEquals(2, store.count());
        List<ScanHistoryItem> items = store.recent(10);
        assertEquals(failureId, items.get(0).id());
        assertEquals(reportId, items.get(1).id());
        assertFalse(items.get(0).aiIncomplete());
        assertFalse(items.get(1).aiIncomplete());
        ScanHistoryDetail restored = store.detail(reportId);
        assertNotNull(restored);
        assertNotNull(restored.report());
        assertEquals(original.overall(), restored.report().overall());
        assertEquals(original.label().rawText(), restored.rawText());
        assertEquals("scan_saved.jpg", restored.item().imageName());
        assertEquals(Collections.singletonList("Milk"), restored.item().allergenLabels());
        assertNull(restored.foodTypeCrossCheck());
        ScanHistoryDetail failure = store.detail(failureId);
        assertNotNull(failure);
        assertTrue(failure.item().isFailure());
        assertEquals("blurred label", failure.rawText());
        assertEquals("Ingredient extraction", failure.item().messageTitle());
        assertEquals("No ingredients found.", failure.messageBody());

        long newId = store.insert(NOW + 2, AiAnalyzer.incompleteReport(original));
        assertTrue(newId > failureId);
        assertTrue(store.recent(1).get(0).aiIncomplete());
    }

    @Test
    public void everyStatusCanBeStoredAndReadBack() {
        // Covers the name()-not-ordinal() contract across the whole enum.
        for (AnalysisReport.Status status : AnalysisReport.Status.values()) {
            ContentValues values = new ContentValues();
            values.put("created_at", NOW);
            values.put("overall_status", status.name());
            values.put("summary_allergens", "");
            long id = database.getWritableDatabase().insert("scans", null, values);
            ScanHistoryDetail detail = store.detail(id);
            assertNotNull(detail);
            assertEquals(status, detail.item().status());
        }
    }

    // ----- Failure rows -----

    @Test
    public void aFailureRowKeepsItsMessageAndHasNoReport() {
        long id = store.insertFailure(NOW, "blurry text", "Couldn't read this label",
                "No text was found in this photo.");

        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertNull("a failed read has no report to rebuild", detail.report());
        assertEquals("Couldn't read this label", detail.item().messageTitle());
        assertEquals("No text was found in this photo.", detail.messageBody());
        assertEquals("blurry text", detail.rawText());
        assertTrue(detail.item().isFailure());
        // Never "not detected": the scan says nothing about the label.
        assertEquals(AnalysisReport.Status.UNREADABLE, detail.item().status());
    }

    // ----- Robustness -----

    @Test
    public void aCorruptedBlobReadsAsNoReportRatherThanThrowing() {
        long id = store.insert(NOW, analyse("Ingredients: milk."));
        assertTrue(store.updateFoodTypeCrossCheck(id, FoodTypeCrossCheck.compare(store.detail(id).report(),
                new FoodTypeEstimate("milk drink", Collections.singletonList(dictionary.byId("MILK"))))));
        ContentValues values = new ContentValues();
        values.put("report_blob", new byte[]{1, 2, 3, 4, 5});
        database.getWritableDatabase().update("scans", values, "_id = ?",
                new String[]{Long.toString(id)});

        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertNull(detail.report());
        assertNull(detail.foodTypeCrossCheck());
        // The denormalised verdict is still there, which is the point of storing it.
        assertEquals(AnalysisReport.Status.CONTAINS, detail.item().status());
    }

    @Test
    public void aRowFromAnIncompatibleBuildIsNotDeserialisedAtAll() {
        long id = store.insert(NOW, analyse("Ingredients: milk."));
        ContentValues values = new ContentValues();
        values.put("report_version", 99);
        database.getWritableDatabase().update("scans", values, "_id = ?",
                new String[]{Long.toString(id)});

        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertNull(detail.report());
        assertEquals(AnalysisReport.Status.CONTAINS, detail.item().status());
    }

    @Test
    public void anUnknownStatusNameReadsAsUnreadableNotAsClear() {
        ContentValues values = new ContentValues();
        values.put("created_at", NOW);
        values.put("overall_status", "SOMETHING_NEW");
        values.put("summary_allergens", "");
        long id = database.getWritableDatabase().insert("scans", null, values);

        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        assertEquals(AnalysisReport.Status.UNREADABLE, detail.item().status());
    }

    @Test
    public void detailOfAMissingRowIsNull() {
        assertNull(store.detail(4242));
    }

    @Test
    public void completedFoodTypeAdviceSurvivesReopeningWithoutChangingLabelColumns() {
        AnalysisReport report = analyse("Ingredients: sugar, salt, cocoa.");
        Allergen milk = dictionary.byId("MILK");
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("chocolate cookies", Collections.singletonList(milk)));
        long id = store.insert(NOW, report);
        store.attachImage(id, "scan_saved.jpg");
        String before = labelColumns(id);

        assertTrue(store.updateFoodTypeCrossCheck(id, advice));
        database.close();
        ScanHistoryDetail restored = store.detail(id);

        assertNotNull(restored);
        assertNotNull(restored.foodTypeCrossCheck());
        assertEquals("chocolate cookies", restored.foodTypeCrossCheck().foodType());
        assertEquals(Collections.singletonList(milk), restored.foodTypeCrossCheck().commonAllergens());
        assertEquals(Collections.singletonList(milk), restored.foodTypeCrossCheck().missingAllergens());
        assertEquals(before, labelColumns(id));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, restored.report().overall());
        assertTrue(restored.report().results().stream()
                .allMatch(result -> result.findings().isEmpty()));
    }

    @Test
    public void successfulNoMismatchReplacesSavedReminderAndNullClearsOnlyAdvice() {
        AnalysisReport report = analyse("Ingredients: sugar, salt, cocoa.");
        FoodTypeCrossCheck mismatch = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Collections.singletonList(dictionary.byId("MILK"))));
        FoodTypeCrossCheck noMismatch = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cocoa", Collections.emptyList()));
        long id = store.insert(NOW, report);
        String before = labelColumns(id);

        assertTrue(store.updateFoodTypeCrossCheck(id, mismatch));
        assertFalse(store.detail(id).foodTypeCrossCheck().missingAllergens().isEmpty());
        assertTrue(store.updateFoodTypeCrossCheck(id, noMismatch));
        assertEquals("cocoa", store.detail(id).foodTypeCrossCheck().foodType());
        assertTrue(store.detail(id).foodTypeCrossCheck().missingAllergens().isEmpty());
        assertTrue(store.updateFoodTypeCrossCheck(id, null));
        assertNull(store.detail(id).foodTypeCrossCheck());
        assertEquals(before, labelColumns(id));
    }

    @Test
    public void adviceCannotAttachToFailureUnreadableDeletedOrDifferentSelectionRows() {
        Allergen milk = dictionary.byId("MILK");
        AnalysisReport milkReport = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singleton(milk));
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(milkReport,
                new FoodTypeEstimate("cookies", Collections.singletonList(milk)));
        long good = store.insert(NOW, milkReport);
        long failure = store.insertFailure(NOW, "blur", "No label", "Retake the label.");
        long unreadable = store.insert(NOW, analyse(""));
        AnalysisReport eggsReport = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singleton(dictionary.byId("EGGS")));
        long different = store.insert(NOW, eggsReport);

        assertFalse(store.updateFoodTypeCrossCheck(failure, advice));
        assertFalse(store.updateFoodTypeCrossCheck(unreadable, advice));
        assertFalse(store.updateFoodTypeCrossCheck(different, advice));
        assertNull(store.detail(failure).foodTypeCrossCheck());
        assertNull(store.detail(unreadable).foodTypeCrossCheck());
        assertNull(store.detail(different).foodTypeCrossCheck());
        store.delete(good);
        assertFalse(store.updateFoodTypeCrossCheck(good, advice));
        assertFalse(store.updateFoodTypeCrossCheck(4242, advice));
        assertNull(store.detail(good));
        assertEquals(3, store.count());
    }

    @Test
    public void invalidAdvisoryDataNeverPreventsTheSavedReportOpening() {
        long id = store.insert(NOW, analyse("Ingredients: sugar, salt, cocoa."));
        String before = labelColumns(id);
        String[] invalid = {
                "not json", "{}",
                "{\"version\":99,\"food_type\":\"cookies\",\"common_allergen_ids\":[\"MILK\"]}",
                "{\"version\":1,\"food_type\":\"cookies\",\"common_allergen_ids\":[\"UNKNOWN\"]}",
                "{\"version\":1,\"food_type\":\"cookies\",\"common_allergen_ids\":[\"MILK\",\"MILK\"]}",
                "{\"version\":1,\"food_type\":\"\",\"common_allergen_ids\":[\"MILK\"]}"
        };
        for (String value : invalid) {
            ContentValues values = new ContentValues();
            values.put(ScanHistoryDatabase.FOOD_TYPE_CROSSCHECK, value);
            database.getWritableDatabase().update("scans", values, "_id=?",
                    new String[]{Long.toString(id)});
            ScanHistoryDetail detail = store.detail(id);
            assertNotNull(detail);
            assertNotNull(detail.report());
            assertNull(detail.foodTypeCrossCheck());
            assertEquals(before, labelColumns(id));
        }
    }

    @Test
    public void versionTwoUpgradeAddsAdviceWithoutChangingExistingRows() {
        AnalysisReport original = analyse("Ingredients: milk powder, sugar, water.");
        long id = store.insert(NOW, original);
        store.attachImage(id, "scan_saved.jpg");
        String before = labelColumns(id);
        // Rebuild the exact previous schema from the current row, retaining ids and blobs.
        SQLiteDatabase db = database.getWritableDatabase();
        db.execSQL("ALTER TABLE scans RENAME TO scans_v3");
        db.execSQL("CREATE TABLE scans (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "created_at INTEGER NOT NULL, overall_status TEXT NOT NULL, "
                + "summary_allergens TEXT NOT NULL DEFAULT '', low_confidence INTEGER NOT NULL DEFAULT 0, "
                + "ai_incomplete INTEGER NOT NULL DEFAULT 0, raw_text TEXT NOT NULL DEFAULT '', "
                + "image_name TEXT, report_blob BLOB, report_version INTEGER NOT NULL DEFAULT 0, "
                + "message_title TEXT, message_body TEXT)");
        db.execSQL("INSERT INTO scans SELECT _id, created_at, overall_status, summary_allergens, "
                + "low_confidence, ai_incomplete, raw_text, image_name, report_blob, report_version, "
                + "message_title, message_body FROM scans_v3");
        db.execSQL("DROP TABLE scans_v3");
        db.execSQL("CREATE INDEX idx_scans_created_at ON scans (created_at DESC, _id DESC)");
        db.setVersion(2);
        database.close();

        assertEquals(3, database.getReadableDatabase().getVersion());
        assertEquals(before, labelColumns(id));
        ScanHistoryDetail restored = store.detail(id);
        assertNotNull(restored.report());
        assertNull(restored.foodTypeCrossCheck());
        assertTrue(store.updateFoodTypeCrossCheck(id, FoodTypeCrossCheck.compare(restored.report(),
                new FoodTypeEstimate("milk drink", Collections.singletonList(dictionary.byId("MILK"))))));
        assertNotNull(store.detail(id).foodTypeCrossCheck());
    }

    @Test
    public void committedAdviceNotifiesOnMainThreadAndListenerCanRemoveItself() throws InterruptedException {
        AnalysisReport report = analyse("Ingredients: sugar, salt, cocoa.");
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Collections.singletonList(dictionary.byId("MILK"))));
        long id = store.insert(NOW, report);
        ScanHistory history = new ScanHistory(context, store, imageDirectory);
        CountDownLatch notified = new CountDownLatch(1);
        AtomicBoolean onMain = new AtomicBoolean();
        AtomicLong savedId = new AtomicLong(-1);
        AtomicInteger calls = new AtomicInteger();
        LongConsumer[] listener = new LongConsumer[1];
        listener[0] = changedId -> {
            onMain.set(Looper.myLooper() == Looper.getMainLooper());
            savedId.set(changedId);
            calls.incrementAndGet();
            history.removeOnCrossCheckChangedListener(listener[0]);
            notified.countDown();
        };
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                history.addOnCrossCheckChangedListener(listener[0]);
                history.updateFoodTypeCrossCheck(id, advice);
            });
            assertTrue("The committed advisory did not notify its observer",
                    notified.await(10, TimeUnit.SECONDS));
            assertTrue(onMain.get());
            assertEquals(id, savedId.get());
            assertEquals("cookies", store.detail(id).foodTypeCrossCheck().foodType());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    history.updateFoodTypeCrossCheck(id, advice));
            awaitHistoryWorker(history);
            assertEquals("A removed listener must not receive queued updates", 1, calls.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    history.removeOnCrossCheckChangedListener(listener[0]));
        }
    }

    @Test
    public void adviceForADeletedRowDoesNotNotifyOrRecreateHistory() throws InterruptedException {
        AnalysisReport report = analyse("Ingredients: sugar, salt, cocoa.");
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Collections.singletonList(dictionary.byId("MILK"))));
        long id = store.insert(NOW, report);
        store.delete(id);
        ScanHistory history = new ScanHistory(context, store, imageDirectory);
        AtomicInteger calls = new AtomicInteger();
        LongConsumer listener = ignored -> calls.incrementAndGet();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                history.addOnCrossCheckChangedListener(listener);
                history.updateFoodTypeCrossCheck(id, advice);
            });
            awaitHistoryWorker(history);
            assertEquals(0, calls.get());
            assertEquals(0, store.count());
            assertNull(store.detail(id));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    history.removeOnCrossCheckChangedListener(listener));
        }
    }

    // ----- Ordering and limits -----

    @Test
    public void rowsComeBackNewestFirst() {
        long older = store.insert(NOW - DAY, analyse("Ingredients: milk."));
        long newer = store.insert(NOW, analyse("Ingredients: soy."));

        List<ScanHistoryItem> items = store.recent(10);
        assertEquals(Arrays.asList(newer, older),
                Arrays.asList(items.get(0).id(), items.get(1).id()));
    }

    @Test
    public void rowsInTheSameMillisecondFallBackToIdOrder() {
        long first = store.insert(NOW, analyse("Ingredients: milk."));
        long second = store.insert(NOW, analyse("Ingredients: soy."));
        assertTrue(second > first);

        List<ScanHistoryItem> items = store.recent(10);
        assertEquals(second, items.get(0).id());
        assertEquals(first, items.get(1).id());
    }

    @Test
    public void limitIsHonoured() {
        for (int i = 0; i < 5; i++) {
            store.insert(NOW - i * DAY, analyse("Ingredients: milk."));
        }
        assertEquals(5, store.count());
        assertEquals(2, store.recent(2).size());
    }

    // ----- Deleting -----

    @Test
    public void deleteRemovesTheRowAndReturnsItsImageName() {
        long id = store.insert(NOW, analyse("Ingredients: milk."));
        store.attachImage(id, ScanHistoryImages.fileNameFor(id));

        assertEquals(ScanHistoryImages.fileNameFor(id), store.delete(id));
        assertEquals(0, store.count());
        assertNull(store.detail(id));
    }

    @Test
    public void deletingAnAlreadyMissingRowIsHarmless() {
        assertNull(store.delete(1234));
        assertEquals(0, store.count());
    }

    @Test
    public void clearEmptiesTheTableAndReportsEveryImage() {
        long first = store.insert(NOW, analyse("Ingredients: milk."));
        long second = store.insert(NOW, analyse("Ingredients: soy."));
        store.attachImage(first, ScanHistoryImages.fileNameFor(first));
        store.attachImage(second, ScanHistoryImages.fileNameFor(second));

        List<String> names = store.clear();
        assertTrue(names.contains(ScanHistoryImages.fileNameFor(first)));
        assertTrue(names.contains(ScanHistoryImages.fileNameFor(second)));
        assertEquals(0, store.count());
    }

    @Test
    public void clearStillNamesTheFileOfARowThatNeverAttachedOne() {
        // A save that crashed between writing the JPEG and attaching it leaves image_name null,
        // but the file is still on disk under its id-derived name.
        long id = store.insert(NOW, analyse("Ingredients: milk."));
        assertTrue(store.clear().contains(ScanHistoryImages.fileNameFor(id)));
    }

    @Test
    public void liveIdsListsEveryRow() {
        long first = store.insert(NOW, analyse("Ingredients: milk."));
        long second = store.insert(NOW, analyse("Ingredients: soy."));
        assertEquals(new java.util.HashSet<>(Arrays.asList(first, second)), store.liveIds());
    }

    // ----- Retention -----

    @Test
    public void deleteOlderThanRemovesOnlyRowsPastTheWindow() {
        long sixDays = store.insert(NOW - 6 * DAY, analyse("Ingredients: milk."));
        long exactlySeven = store.insert(ScanRetention.cutoff(NOW), analyse("Ingredients: soy."));
        long eightDays = store.insert(NOW - 8 * DAY, analyse("Ingredients: wheat flour."));
        store.attachImage(eightDays, ScanHistoryImages.fileNameFor(eightDays));

        List<String> names = store.deleteOlderThan(ScanRetention.cutoff(NOW));

        assertTrue("the expired row's image should be reported",
                names.contains(ScanHistoryImages.fileNameFor(eightDays)));
        assertFalse(names.contains(ScanHistoryImages.fileNameFor(sixDays)));
        assertEquals(new java.util.HashSet<>(Arrays.asList(sixDays, exactlySeven)),
                store.liveIds());
    }

    @Test
    public void deleteOlderThanOnFreshHistoryRemovesNothing() {
        store.insert(NOW, analyse("Ingredients: milk."));
        assertTrue(store.deleteOlderThan(ScanRetention.cutoff(NOW)).isEmpty());
        assertEquals(1, store.count());
    }

    // ----- Helpers -----

    private static void awaitHistoryWorker(ScanHistory history) throws InterruptedException {
        CountDownLatch idle = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                history.load(1, items -> idle.countDown()));
        assertTrue("The history worker did not drain", idle.await(10, TimeUnit.SECONDS));
    }

    private String labelColumns(long id) {
        try (Cursor row = database.getReadableDatabase().rawQuery("SELECT * FROM scans WHERE _id=?",
                new String[]{Long.toString(id)})) {
            assertTrue(row.moveToFirst());
            StringBuilder result = new StringBuilder();
            // Order by name because the v2 upgrade appends its new column after all old fields.
            java.util.Map<String, String> values = new java.util.TreeMap<>();
            for (int index = 0; index < row.getColumnCount(); index++) {
                String name = row.getColumnName(index);
                if (ScanHistoryDatabase.FOOD_TYPE_CROSSCHECK.equals(name)) continue;
                values.put(name, row.isNull(index) ? "<null>" : row.getType(index) == Cursor.FIELD_TYPE_BLOB
                        ? Arrays.toString(row.getBlob(index)) : row.getString(index));
            }
            for (java.util.Map.Entry<String, String> value : values.entrySet()) {
                result.append(value.getKey()).append('=').append(value.getValue()).append('\u001f');
            }
            return result.toString();
        }
    }

    private AnalysisReport analyse(String text) {
        Collection<Allergen> selected = dictionary.allergens();
        return Analyzer.analyse(text, dictionary, selected);
    }

    /** The dictionary the app ships; {@code res/raw} is reachable here, unlike in a JVM test. */
    private AllergenDictionary shippedDictionary() throws IOException {
        int id = context.getResources().getIdentifier(
                "allergen_dictionary", "raw", context.getPackageName());
        assertTrue("allergen_dictionary.json should be on the device", id != 0);
        try (InputStream in = context.getResources().openRawResource(id);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return AllergenDictionary.load(reader);
        }
    }
}
