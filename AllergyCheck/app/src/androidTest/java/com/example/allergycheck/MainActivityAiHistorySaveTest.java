package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AiModel;
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

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Real OCR and history wiring, with deterministic AI replies and no network or model download. */
@RunWith(AndroidJUnit4.class)
public class MainActivityAiHistorySaveTest {
    private Context context;
    private ScanHistoryDatabase database;
    private ScanHistoryStore store;
    private File imageDirectory;
    private Set<Long> preexisting;
    private ScanTestSupport support;
    private final Map<SharedPreferences, Map<String, ?>> originalPreferences = new HashMap<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[] {
                "analysis_preferences", "analysis_credentials", "allergy_preferences"}) {
            SharedPreferences preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            Map<String, Object> snapshot = new HashMap<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                Object value = entry.getValue();
                snapshot.put(entry.getKey(), value instanceof Set
                        ? new HashSet<>((Set<?>) value) : value);
            }
            originalPreferences.put(preferences, snapshot);
        }
        assertTrue(context.getSharedPreferences("analysis_preferences", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("mode", AnalysisPreferences.Mode.OPENROUTER.name())
                .putString("openrouter_model", "history-test-model")
                .putString("cloud_concurrency", "1").commit());
        assertTrue(context.getSharedPreferences("analysis_credentials", Context.MODE_PRIVATE)
                .edit().clear().putString("openrouter_api_key", "history-test-key").commit());
        // None of this fixture's ingredients matches Sesame through the rule matcher, so the
        // model's answer must actually change the report before it can be saved.
        assertTrue(AllergenPreferences.preferences(context).edit().clear()
                .putStringSet("selected_allergens", Collections.singleton("SESAME")).commit());
        database = new ScanHistoryDatabase(context);
        imageDirectory = new File(context.getFilesDir(), "history");
        store = new ScanHistoryStore(database, imageDirectory);
        preexisting = new HashSet<>(store.liveIds());
        support = new ScanTestSupport(context);
    }

    @After
    public void tearDown() throws Exception {
        try {
            drainHistory();
            for (long id : newRowIds()) {
                store.delete(id);
                ScanHistoryImages.delete(imageDirectory, ScanHistoryImages.fileNameFor(id));
            }
        } finally {
            if (database != null) database.close();
            if (support != null) support.close();
            for (Map.Entry<SharedPreferences, Map<String, ?>> entry : originalPreferences.entrySet()) {
                restorePreferences(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    public void historyWaitsForAiAndSavesTheFinalReportExactlyOnce() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Salt.");
        BlockingModel model = new BlockingModel();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            AiAnalysisRunner runner = replaceRunner(scan, model, 30_000);
            try {
                support.loadPhoto(scan, photo);
                ScanTestSupport.startCheck(scan);
                assertTrue("AI never started after OCR", model.entered.await(20, TimeUnit.SECONDS));
                drainHistory();
                assertTrue("The preliminary rule report must not be saved", newRowIds().isEmpty());
                scan.onActivity(activity -> assertFalse(
                        activity.findViewById(R.id.analysis_result).isShown()));

                model.release.countDown();
                awaitRunnerIdle(runner);
                awaitResult(scan);
                drainHistory();

                ScanHistoryDetail detail = onlyNewReport();
                assertTrue(detail.report().aiAnalysis());
                assertFalse(detail.report().aiIncomplete());
                assertEquals(AnalysisReport.Status.POSSIBLE, detail.report().overall());
                assertEquals(detail.report().overall(), detail.item().status());
                assertTrue("History must retain the AI's ingredient evidence",
                        hasFinding(detail.report(), Finding.Reason.AI_RISK));
                AnalysisMessage message = new AnalysisMessage(context.getResources());
                scan.onActivity(activity -> assertEquals(message.title(detail.report()),
                        ((TextView) activity.findViewById(R.id.verdict_title)).getText().toString()));
            } finally {
                model.release.countDown();
                awaitRunnerIdle(runner);
            }
        }
    }

    @Test
    public void cancellingDuringAiDoesNotSaveThePreliminaryOrLateReport() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Salt.");
        BlockingModel model = new BlockingModel();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            AiAnalysisRunner runner = replaceRunner(scan, model, 30_000);
            try {
                support.loadPhoto(scan, photo);
                ScanTestSupport.startCheck(scan);
                assertTrue("AI never started after OCR", model.entered.await(20, TimeUnit.SECONDS));
                drainHistory();
                assertTrue("A pending AI scan must not be recorded", newRowIds().isEmpty());
                scan.onActivity(activity -> assertTrue(
                        activity.findViewById(R.id.cancel_analysis_button).performClick()));

                model.release.countDown();
                assertTrue("The late model reply never returned",
                        model.returned.await(10, TimeUnit.SECONDS));
                awaitRunnerIdle(runner);
                drainHistory();
                assertTrue("A cancelled AI scan must not be recorded", newRowIds().isEmpty());
            } finally {
                model.release.countDown();
                awaitRunnerIdle(runner);
            }
        }
    }

    @Test
    public void timeoutSavesOneIncompleteReportAndLateAiCannotReplaceIt() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Salt.");
        BlockingModel model = new BlockingModel();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            AiAnalysisRunner runner = replaceRunner(scan, model, 2_000);
            try {
                support.loadPhoto(scan, photo);
                ScanTestSupport.startCheck(scan);
                assertTrue("AI never started after OCR", model.entered.await(20, TimeUnit.SECONDS));
                awaitResult(scan);
                drainHistory();
                ScanHistoryDetail timedOut = onlyNewReport();
                assertTrue(timedOut.report().aiAnalysis());
                assertTrue(timedOut.report().aiIncomplete());
                assertTrue(hasFinding(timedOut.report(), Finding.Reason.AI_UNAVAILABLE));
                assertFalse(hasFinding(timedOut.report(), Finding.Reason.AI_RISK));

                model.release.countDown();
                assertTrue("The late model reply never returned",
                        model.returned.await(10, TimeUnit.SECONDS));
                awaitRunnerIdle(runner);
                drainHistory();
                ScanHistoryDetail afterLateReply = onlyNewReport();
                assertEquals(timedOut.item().id(), afterLateReply.item().id());
                assertTrue("The stored verdict must remain the one shown on timeout",
                        afterLateReply.report().aiIncomplete());
                assertFalse(hasFinding(afterLateReply.report(), Finding.Reason.AI_RISK));
            } finally {
                model.release.countDown();
                awaitRunnerIdle(runner);
            }
        }
    }

    @Test
    public void cancellingWhileTheFinalRingClosesDoesNotRecordTheResult() throws Exception {
        assumeTrue("This boundary requires an animated completion ring",
                ValueAnimator.areAnimatorsEnabled());
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Salt.");
        BlockingModel model = new BlockingModel();
        AnalysisReport matching = Analyzer.analyse(Arrays.asList("Sugar", "Salt"),
                "INGREDIENTS: Sugar, Salt.", AllergenDictionaryProvider.get(context),
                AllergenPreferences.selectedAllergenSet(context, AllergenDictionaryProvider.get(context)));
        AnalysisReport completed = AiAnalyzer.analyse(matching,
                (ingredient, allergen) -> AiDecision.of(AiDecision.Verdict.RISK));
        Class<?> actionType = Class.forName(MainActivity.class.getName() + "$ResultAction");
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            AiAnalysisRunner runner = replaceRunner(scan, model, 30_000);
            try {
                support.loadPhoto(scan, photo);
                ScanTestSupport.startCheck(scan);
                assertTrue("AI never started after OCR", model.entered.await(20, TimeUnit.SECONDS));
                scan.onActivity(activity -> {
                    // Deliver a completed report and cancel within one UI turn. Resetting the
                    // ring makes the reveal deferred regardless of the OCR/device speed.
                    runner.cancel();
                    try {
                        Field field = MainActivity.class.getDeclaredField("scanProgress");
                        field.setAccessible(true);
                        ((ScanProgress) field.get(activity)).reset(true);
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError(exception);
                    }
                    ScanTestSupport.invoke(activity, "showAnalysisReport",
                            new Class<?>[] {Uri.class, AnalysisReport.class, actionType},
                            support.photoUri(photo), completed, null);
                    assertEquals(View.GONE,
                            activity.findViewById(R.id.analysis_result).getVisibility());
                    assertTrue(activity.findViewById(R.id.cancel_analysis_button).performClick());
                });
                model.release.countDown();
                awaitRunnerIdle(runner);
                drainHistory();
                assertTrue("Cancellation before the result reveal must drop its history save",
                        newRowIds().isEmpty());
            } finally {
                model.release.countDown();
                awaitRunnerIdle(runner);
            }
        }
    }

    @Test
    public void aiFinishingOnTheHistoryTabKeepsScanControlsHiddenAndRecordsOnce() throws Exception {
        File photo = support.createLabel("INGREDIENTS:", "Sugar, Salt.");
        BlockingModel model = new BlockingModel();
        try (ScanTestSupport.Scan scan = ScanTestSupport.launch(context)) {
            AiAnalysisRunner runner = replaceRunner(scan, model, 30_000);
            try {
                support.loadPhoto(scan, photo);
                ScanTestSupport.startCheck(scan);
                assertTrue("AI never started after OCR", model.entered.await(20, TimeUnit.SECONDS));
                scan.onActivity(activity -> ((BottomNavigationView)
                        activity.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.tab_history));
                model.release.countDown();
                awaitRunnerIdle(runner);
                awaitResultBound(scan);
                drainHistory();
                assertTrue(onlyNewReport().report().aiAnalysis());
                scan.onActivity(activity -> {
                    assertTrue(activity.findViewById(R.id.history_container).isShown());
                    assertFalse(activity.findViewById(R.id.analysis_result).isShown());
                    assertEquals("Finishing a hidden scan must not reveal its restart button",
                            View.GONE, activity.findViewById(R.id.restart_button).getVisibility());
                    ((BottomNavigationView) activity.findViewById(R.id.bottom_nav))
                            .setSelectedItemId(R.id.tab_scan);
                });
                awaitResult(scan);
                scan.onActivity(activity -> assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.restart_button).getVisibility()));
                drainHistory();
                onlyNewReport();
            } finally {
                model.release.countDown();
                awaitRunnerIdle(runner);
            }
        }
    }

    private AiAnalysisRunner replaceRunner(ScanTestSupport.Scan scan, AiModel model, long timeout) {
        AiAnalysisRunner[] replacement = new AiAnalysisRunner[1];
        scan.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("aiRunner");
                field.setAccessible(true);
                ((AiAnalysisRunner) field.get(activity)).close();
                replacement[0] = new AiAnalysisRunner(context, () -> model, timeout);
                field.set(activity, replacement[0]);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        });
        return replacement[0];
    }

    /** A queue barrier, so row counts also catch duplicate saves queued after the first one. */
    private void drainHistory() throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ScanHistory.get(context).load(ScanHistory.DEFAULT_LIMIT, items -> drained.countDown()));
        assertTrue("History worker did not finish", drained.await(10, TimeUnit.SECONDS));
    }

    private static void awaitRunnerIdle(AiAnalysisRunner runner) throws Exception {
        Field field = AiAnalysisRunner.class.getDeclaredField("worker");
        field.setAccessible(true);
        ExecutorService worker = (ExecutorService) field.get(runner);
        worker.submit(() -> { }).get(10, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private Set<Long> newRowIds() {
        Set<Long> ids = new HashSet<>(store.liveIds());
        ids.removeAll(preexisting);
        return ids;
    }

    private ScanHistoryDetail onlyNewReport() {
        Set<Long> ids = newRowIds();
        assertEquals("Exactly one finished report should be saved: " + ids, 1, ids.size());
        ScanHistoryDetail detail = store.detail(ids.iterator().next());
        assertNotNull(detail);
        assertNotNull("The history row must contain the final report", detail.report());
        return detail;
    }

    private static boolean hasFinding(AnalysisReport report, Finding.Reason reason) {
        return report.results().stream().flatMap(result -> result.findings().stream())
                .anyMatch(finding -> finding.reason() == reason && !finding.evidence().isEmpty());
    }

    private static void awaitResult(ScanTestSupport.Scan scan) {
        awaitResult(scan, true);
    }

    private static void awaitResultBound(ScanTestSupport.Scan scan) {
        awaitResult(scan, false);
    }

    private static void awaitResult(ScanTestSupport.Scan scan, boolean requireShown) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        do {
            boolean[] shown = {false};
            scan.onActivity(activity -> {
                View result = activity.findViewById(R.id.analysis_result);
                shown[0] = result != null && (requireShown ? result.isShown()
                        : result.getVisibility() == View.VISIBLE);
            });
            if (shown[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No AI result appeared");
    }

    @SuppressWarnings("unchecked")
    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else throw new AssertionError("Unexpected preference value for " + key);
        }
        assertTrue(editor.commit());
    }

    private static final class BlockingModel implements AiModel {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);

        @Override
        public AiDecision evaluate(String ingredient, Allergen allergen) {
            throw new AssertionError("The cloud mode should use one ingredient batch");
        }

        @Override
        public boolean supportsBatch() { return true; }

        @Override
        public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) {
            entered.countDown();
            try {
                // Deliberately outlive cancellation, as a model or HTTP call can do. The tests
                // release this in finally, and the deadline also prevents a stranded worker.
                long deadline = SystemClock.uptimeMillis() + 45_000;
                boolean released = false;
                while (!released && SystemClock.uptimeMillis() < deadline) {
                    try {
                        released = release.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Return a late successful answer even after the caller has cancelled.
                    }
                }
                if (!released) throw new AssertionError("Test did not release the model");
                List<AiDecision> decisions = new ArrayList<>();
                for (String ignored : ingredients) {
                    decisions.add(AiDecision.of(AiDecision.Verdict.RISK));
                }
                return decisions;
            } finally {
                returned.countDown();
            }
        }
    }
}
