package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;
import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.history.ScanHistoryDatabase;
import com.example.allergycheck.history.ScanHistoryDetail;
import com.example.allergycheck.history.ScanHistoryImages;
import com.example.allergycheck.history.ScanHistoryStore;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** The real scan/photo/history flow with a deterministic vision reply; never contacts a provider. */
@RunWith(AndroidJUnit4.class)
public class MainCrossCheckResultTest {
    private Context context;
    private ScanTestSupport support;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private File historyImages;
    private Set<Long> oldHistory;
    private final Map<String, Map<String, ?>> previousPreferences = new HashMap<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[]{"analysis_preferences", "analysis_credentials", "allergy_preferences"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            previousPreferences.put(name, new HashMap<>(preferences.getAll()));
            assertTrue(preferences.edit().clear().commit());
        }
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.MATCHING);
        AnalysisPreferences.saveOpenRouterApiKey(context, "synthetic-crosscheck-key");
        AnalysisPreferences.saveFoodTypeModel(context, "test/vision");
        AllergenPreferences.saveSelectedAllergenIds(context, Collections.singleton("MILK"));
        AllergenPreferences.saveShowNotDetected(context, false);
        support = new ScanTestSupport(context);
        database = new ScanHistoryDatabase(context);
        historyImages = new File(context.getFilesDir(), "history");
        store = new ScanHistoryStore(database, historyImages);
        oldHistory = new HashSet<>(store.liveIds());
    }

    @After
    public void tearDown() throws Exception {
        try {
            drainHistory();
            for (long id : store.liveIds()) if (!oldHistory.contains(id)) {
                store.delete(id);
                ScanHistoryImages.delete(historyImages, ScanHistoryImages.fileNameFor(id));
            }
        } finally {
            if (database != null) database.close();
            if (support != null) support.close();
            for (Map.Entry<String, Map<String, ?>> entry : previousPreferences.entrySet()) {
                restore(context.getSharedPreferences(entry.getKey(), Context.MODE_PRIVATE), entry.getValue());
            }
        }
    }

    @Test
    public void photoMismatchIsSavedAndSurvivesLiveRemovalAndStartingAnotherScan() throws Exception {
        File label = support.createLabel("INGREDIENTS:", "Sugar, Salt, Cocoa.");
        File front = support.createLabel("FRONT PHOTO: COOKIES");
        Allergen milk = AllergenDictionaryProvider.get(context).byId("MILK");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            scan.onActivity(activity -> {
                // Keep the production controller and its registered main-result listener. Only
                // its provider factory is replaced, before any image can trigger a request.
                FoodCrossCheckController.ModelFactory fake = (key, model) -> new FoodCrossCheckController.FrontPhotoModel() {
                    @Override public FoodTypeEstimate estimate(String image, List<Allergen> allergens) {
                        assertEquals(Collections.singletonList(milk), allergens);
                        return new FoodTypeEstimate("chocolate cookies", Collections.singletonList(milk));
                    }
                    @Override public void close() { }
                };
                setField(controller(activity), "models", fake);
            });
            support.loadPhoto(scan, label);
            ScanTestSupport.startCheck(scan);
            await(scan, activity -> field(controller(activity), "report") != null
                    && activity.findViewById(R.id.analysis_result).isShown());
            AtomicReference<AnalysisReport> original = new AtomicReference<>();
            AtomicReference<String> originalText = new AtomicReference<>();
            scan.onActivity(activity -> {
                original.set((AnalysisReport) field(controller(activity), "report"));
                assertEquals(AnalysisReport.Status.NOT_DETECTED, original.get().overall());
                assertEquals(0, ((ViewGroup) activity.findViewById(R.id.allergen_rows)).getChildCount());
                originalText.set(visibleText(activity.findViewById(R.id.analysis_result)));
            });
            byte[] reportBytes = bytes(original.get());
            drainHistory();
            Set<Long> savedIds = new HashSet<>(store.liveIds());
            Set<Long> newIds = new HashSet<>(savedIds);
            newIds.removeAll(oldHistory);
            assertEquals("The ingredient scan must be saved exactly once", 1, newIds.size());
            long id = newIds.iterator().next();
            HistorySnapshot saved = history(id);

            scan.onActivity(activity -> controller(activity).loadPhoto(support.photoUri(front)));
            await(scan, activity -> activity.getString(R.string.crosscheck_review_title)
                    .contentEquals(((TextView) activity.findViewById(R.id.verdict_title)).getText()));
            scan.onActivity(activity -> {
                assertSame(original.get(), field(controller(activity), "report"));
                assertArrayEquals(reportBytes, bytes(original.get()));
                String summary = ((TextView) activity.findViewById(R.id.verdict_message)).getText().toString();
                assertTrue(summary.contains("chocolate cookies"));
                assertTrue(summary.contains(milk.label()));
                assertTrue(summary.contains("does not confirm"));
                TextView title = activity.findViewById(R.id.verdict_title);
                assertEquals(activity.getString(R.string.result_ready, title.getText()),
                        title.getAccessibilityPaneTitle().toString());
                ViewGroup rows = activity.findViewById(R.id.allergen_rows);
                assertEquals("The mismatch must remain visible when unmatched rows are hidden", 1, rows.getChildCount());
                View row = rows.getChildAt(0);
                assertEquals(activity.getString(R.string.crosscheck_review_status),
                        ((TextView) row.findViewById(R.id.allergen_status)).getText().toString());
                assertTrue(row.performClick());
                TextView evidence = row.findViewById(R.id.allergen_evidence);
                assertEquals(View.VISIBLE, evidence.getVisibility());
                assertTrue(evidence.getText().toString().contains("Ingredient label: Not detected."));
                assertTrue(original.get().results().get(0).findings().isEmpty());
            });
            captureIfRequested(scan);
            drainHistory();
            assertArrayEquals(saved.report, history(id).report);
            assertEquals(saved.columns, history(id).columns);
            assertEquals(savedIds, store.liveIds());
            assertStoredAdvice(id, "chocolate cookies", milk);

            scan.onActivity(activity -> {
                controller(activity).removePhoto();
                assertEquals(originalText.get(), visibleText(activity.findViewById(R.id.analysis_result)));
                assertEquals(0, ((ViewGroup) activity.findViewById(R.id.allergen_rows)).getChildCount());
                assertArrayEquals(reportBytes, bytes((AnalysisReport) field(controller(activity), "report")));
                TextView title = activity.findViewById(R.id.verdict_title);
                assertEquals(activity.getString(R.string.result_ready, title.getText()),
                        title.getAccessibilityPaneTitle().toString());
            });
            drainHistory();
            assertArrayEquals(saved.report, history(id).report);
            assertEquals(saved.columns, history(id).columns);
            assertEquals(savedIds, store.liveIds());
            assertStoredAdvice(id, "chocolate cookies", milk);
            scan.onActivity(activity -> assertTrue(activity.findViewById(R.id.restart_button).performClick()));
            drainHistory();
            assertStoredAdvice(id, "chocolate cookies", milk);
            assertArrayEquals(saved.report, history(id).report);
            assertEquals(savedIds, store.liveIds());
            ScanHistoryDetail savedDetail = store.detail(id);
            scan.onActivity(activity -> {
                ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.tab_history);
                activity.getSupportFragmentManager().executePendingTransactions();
                HistoryFragment history = (HistoryFragment) activity.getSupportFragmentManager()
                        .findFragmentById(R.id.history_container);
                assertNotNull(history);
                history.onScanClicked(savedDetail.item());
                activity.getSupportFragmentManager().executePendingTransactions();
            });
            await(scan, activity -> {
                View result = activity.findViewById(R.id.history_container).findViewById(R.id.history_result);
                return result != null && result.isShown() && activity.getString(R.string.crosscheck_review_title)
                        .contentEquals(((TextView) result.findViewById(R.id.verdict_title)).getText());
            });
            scan.onActivity(activity -> {
                View result = activity.findViewById(R.id.history_container).findViewById(R.id.history_result);
                assertTrue(((TextView) result.findViewById(R.id.verdict_message)).getText().toString()
                        .contains("chocolate cookies"));
                View row = ((ViewGroup) result.findViewById(R.id.allergen_rows)).getChildAt(0);
                assertEquals(milk.label(), ((TextView) row.findViewById(R.id.allergen_name)).getText().toString());
                assertEquals(activity.getString(R.string.crosscheck_review_status),
                        ((TextView) row.findViewById(R.id.allergen_status)).getText().toString());
                assertTrue(row.performClick());
                TextView evidence = row.findViewById(R.id.allergen_evidence);
                assertEquals(View.VISIBLE, evidence.getVisibility());
                assertTrue(evidence.getText().toString().contains("Ingredient label: Not detected."));
            });
            captureHistoryIfRequested(scan);
        }
        // A separate database connection rebuilds the advisory from disk after the live activity
        // and its transient photo state have gone away.
        try (ScanHistoryDatabase reopened = new ScanHistoryDatabase(context)) {
            ScanHistoryDetail detail = new ScanHistoryStore(reopened, historyImages).detail(idSavedAfterScan());
            assertNotNull(detail);
            assertNotNull(detail.foodTypeCrossCheck());
            assertEquals("chocolate cookies", detail.foodTypeCrossCheck().foodType());
        }
    }

    @Test
    public void delayedSaveCallbacksKeepAdviceWithTheScanThatProducedIt() throws Exception {
        File firstLabel = support.createLabel("INGREDIENTS:", "Sugar, Salt, Cocoa.");
        File secondLabel = support.createLabel("INGREDIENTS:", "Oats, Honey, Salt.");
        File front = support.createLabel("FRONT PHOTO: COOKIES");
        Allergen milk = AllergenDictionaryProvider.get(context).byId("MILK");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<AnalysisReport> firstReport = new AtomicReference<>();
        AtomicReference<AnalysisReport> secondReport = new AtomicReference<>();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            drainHistory();
            scan.onActivity(activity -> {
                setField(controller(activity), "models", (FoodCrossCheckController.ModelFactory) (key, model) ->
                        new FoodCrossCheckController.FrontPhotoModel() {
                            @Override public FoodTypeEstimate estimate(String image, List<Allergen> allergens) {
                                return new FoodTypeEstimate("first scan cookies", Collections.singletonList(milk));
                            }
                            @Override public void close() { }
                        });
                ((ExecutorService) field(ScanHistory.get(context), "worker")).execute(() -> {
                    blocked.countDown();
                    try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                });
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            try {
                support.loadPhoto(scan, firstLabel);
                ScanTestSupport.startCheck(scan);
                await(scan, activity -> field(controller(activity), "report") != null);
                scan.onActivity(activity -> {
                    firstReport.set((AnalysisReport) field(controller(activity), "report"));
                    controller(activity).loadPhoto(support.photoUri(front));
                });
                await(scan, activity -> activity.getString(R.string.crosscheck_review_title)
                        .contentEquals(((TextView) activity.findViewById(R.id.verdict_title)).getText()));
                scan.onActivity(activity -> assertTrue(activity.findViewById(R.id.restart_button).performClick()));
                support.loadPhoto(scan, secondLabel);
                ScanTestSupport.startCheck(scan);
                await(scan, activity -> field(controller(activity), "report") != null
                        && field(controller(activity), "report") != firstReport.get());
                scan.onActivity(activity -> secondReport.set((AnalysisReport) field(controller(activity), "report")));
            } finally {
                release.countDown();
            }
            drainHistory();
            long firstId = awaitStoredReport(firstReport.get());
            long secondId = awaitStoredReport(secondReport.get());
            assertNotEquals(firstId, secondId);
            awaitStoredAdvice(firstId, "first scan cookies", milk);
            assertNull("The next scan must not inherit the previous scan's photo reminder",
                    store.detail(secondId).foodTypeCrossCheck());
            assertEquals("Only the two ingredient scans may create history rows", oldHistory.size() + 2,
                    store.liveIds().size());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void failedRetryKeepsSavedWarningAndSuccessfulNoMismatchReplacesIt() throws Exception {
        File label = support.createLabel("INGREDIENTS:", "Sugar, Salt, Cocoa.");
        File front = support.createLabel("FRONT PHOTO: COOKIES");
        Allergen milk = AllergenDictionaryProvider.get(context).byId("MILK");
        AtomicInteger calls = new AtomicInteger();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            scan.onActivity(activity -> setField(controller(activity), "models",
                    (FoodCrossCheckController.ModelFactory) (key, model) -> new FoodCrossCheckController.FrontPhotoModel() {
                        @Override public FoodTypeEstimate estimate(String image, List<Allergen> allergens) throws Exception {
                            int attempt = calls.incrementAndGet();
                            if (attempt == 2) throw new java.io.IOException("synthetic retry failure");
                            return new FoodTypeEstimate("chocolate cookies", attempt == 1
                                    ? Collections.singletonList(milk) : Collections.emptyList());
                        }
                        @Override public void close() { }
                    }));
            support.loadPhoto(scan, label);
            ScanTestSupport.startCheck(scan);
            await(scan, activity -> field(controller(activity), "report") != null);
            drainHistory();
            long id = idSavedAfterScan();
            HistorySnapshot labelSnapshot = history(id);
            scan.onActivity(activity -> controller(activity).loadPhoto(support.photoUri(front)));
            await(scan, activity -> activity.getString(R.string.crosscheck_review_title)
                    .contentEquals(((TextView) activity.findViewById(R.id.verdict_title)).getText()));
            drainHistory();
            assertStoredAdvice(id, "chocolate cookies", milk);

            scan.onActivity(activity -> controller(activity).runCheck());
            await(scan, activity -> ((TextView) activity.findViewById(R.id.front_crosscheck_message))
                    .getText().toString().contains("could not be completed"));
            drainHistory();
            assertStoredAdvice(id, "chocolate cookies", milk);

            scan.onActivity(activity -> controller(activity).runCheck());
            await(scan, activity -> field(controller(activity), "estimate") != null
                    && ((FoodTypeEstimate) field(controller(activity), "estimate")).commonAllergens().isEmpty());
            drainHistory();
            FoodTypeCrossCheck latest = store.detail(id).foodTypeCrossCheck();
            assertNotNull("The latest completed food-type check is retained", latest);
            assertTrue("A completed no-mismatch result replaces the previous stored warning",
                    latest.missingAllergens().isEmpty());
            assertArrayEquals(labelSnapshot.report, history(id).report);
            assertEquals(labelSnapshot.columns, history(id).columns);
            assertEquals(3, calls.get());
            assertEquals(oldHistory.size() + 1, store.liveIds().size());
            scan.onActivity(activity -> {
                assertEquals(activity.getString(R.string.status_not_detected_title),
                        ((TextView) activity.findViewById(R.id.verdict_title)).getText().toString());
                assertEquals(0, ((ViewGroup) activity.findViewById(R.id.allergen_rows)).getChildCount());
            });
        }
    }

    private long idSavedAfterScan() {
        Set<Long> saved = new HashSet<>(store.liveIds());
        saved.removeAll(oldHistory);
        assertEquals(1, saved.size());
        return saved.iterator().next();
    }

    private void assertStoredAdvice(long id, String foodType, Allergen allergen) {
        ScanHistoryDetail detail = store.detail(id);
        assertNotNull(detail);
        FoodTypeCrossCheck advice = detail.foodTypeCrossCheck();
        assertNotNull("Completed photo reminders must be saved with the original scan", advice);
        assertEquals(foodType, advice.foodType());
        assertEquals(Collections.singletonList(allergen), advice.missingAllergens());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, detail.report().overall());
        assertTrue(detail.report().results().get(0).findings().isEmpty());
    }

    private void awaitStoredAdvice(long id, String foodType, Allergen allergen) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            ScanHistoryDetail detail = store.detail(id);
            if (detail != null && detail.foodTypeCrossCheck() != null) {
                assertStoredAdvice(id, foodType, allergen);
                return;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The completed reminder was not attached to its saved scan");
    }

    private long awaitStoredReport(AnalysisReport expected) {
        byte[] expectedBytes = bytes(expected);
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            for (long id : store.liveIds()) {
                if (!oldHistory.contains(id) && java.util.Arrays.equals(expectedBytes, history(id).report)) return id;
            }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The ingredient scan was not saved");
    }

    private void captureIfRequested(ScanTestSupport.Scan scan) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureMainCrossCheckResult"))) return;
        scan.onActivity(activity -> {
            View verdict = activity.findViewById(R.id.verdict_card);
            ViewGroup content = activity.findViewById(R.id.content_column);
            Rect at = new Rect();
            verdict.getDrawingRect(at);
            content.offsetDescendantRectToMyCoords(verdict, at);
            ((NestedScrollView) activity.findViewById(R.id.content_scroll)).scrollTo(0, Math.max(0, at.top - 16));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        saveViewport("main-crosscheck-result.png");
    }

    private void captureHistoryIfRequested(ScanTestSupport.Scan scan) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureHistoryCrossCheckResult"))) return;
        scan.onActivity(activity -> {
            View verdict = activity.findViewById(R.id.history_container).findViewById(R.id.verdict_card);
            View ancestor = (View) verdict.getParent();
            while (!(ancestor instanceof NestedScrollView)) ancestor = (View) ancestor.getParent();
            NestedScrollView scroll = (NestedScrollView) ancestor;
            Rect at = new Rect();
            verdict.getDrawingRect(at);
            ((ViewGroup) scroll.getChildAt(0)).offsetDescendantRectToMyCoords(verdict, at);
            scroll.scrollTo(0, Math.max(0, at.top - 8));
        });
        SystemClock.sleep(context.getResources().getInteger(android.R.integer.config_shortAnimTime) + 100L);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        saveViewport("history-crosscheck-result.png");
    }

    private void saveViewport(String fileName) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream output = new FileOutputStream(new File(context.getCacheDir(), fileName))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }

    private void drainHistory() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ScanHistory.get(context).load(ScanHistory.DEFAULT_LIMIT, items -> done.countDown()));
        assertTrue("History worker did not finish", done.await(10, TimeUnit.SECONDS));
    }

    private HistorySnapshot history(long id) {
        try (Cursor row = database.getReadableDatabase().rawQuery("SELECT * FROM scans WHERE _id=?", new String[]{Long.toString(id)})) {
            assertTrue(row.moveToFirst());
            byte[] report = row.getBlob(row.getColumnIndexOrThrow("report_blob"));
            StringBuilder columns = new StringBuilder();
            for (int i = 0; i < row.getColumnCount(); i++) if (!"report_blob".equals(row.getColumnName(i))
                    && !"food_type_crosscheck".equals(row.getColumnName(i))) {
                columns.append(row.getColumnName(i)).append('=').append(row.isNull(i) ? "<null>" : row.getString(i)).append('\u001f');
            }
            return new HistorySnapshot(report, columns.toString());
        }
    }

    private static final class HistorySnapshot {
        final byte[] report;
        final String columns;
        HistorySnapshot(byte[] report, String columns) { this.report = report; this.columns = columns; }
    }

    private static void await(ScanTestSupport.Scan scan, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 30_000;
        do {
            boolean[] ready = {false};
            scan.onActivity(activity -> ready[0] = condition.test(activity));
            if (ready[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("The main result did not reach the expected state");
    }

    private static FoodCrossCheckController controller(MainActivity activity) { return (FoodCrossCheckController) field(activity, "foodCrossCheck"); }
    private static Object field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void setField(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static byte[] bytes(AnalysisReport report) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
            return bytes.toByteArray();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static String visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return "";
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) text.append(visibleText(((ViewGroup) view).getChildAt(i)));
        return text.toString();
    }
    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> before) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
        }
        assertTrue(editor.commit());
    }
}
