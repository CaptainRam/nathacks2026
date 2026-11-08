package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AiModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercise real main-loop/worker cancellation and timeout boundaries using a blocked fake model. */
@RunWith(AndroidJUnit4.class)
public class AiAnalysisRunnerTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> originalConfiguration;

    @Before
    public void isolateAnalysisConfiguration() {
        originalConfiguration = new HashMap<>(context.getSharedPreferences(
                "analysis_preferences", Context.MODE_PRIVATE).getAll());
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.LOCAL_AI);
        AnalysisPreferences.saveCloudConcurrency(context, 1);
    }

    @After
    @SuppressWarnings("unchecked")
    public void restoreAnalysisConfiguration() {
        SharedPreferences.Editor editor = context.getSharedPreferences(
                "analysis_preferences", Context.MODE_PRIVATE).edit().clear();
        for (Map.Entry<String, ?> entry : originalConfiguration.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
        }
        assertTrue(editor.commit());
    }

    @Test
    public void cancellationDuringBlockedPairStopsFurtherRequestsAndDropsLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch modelClosed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Thread> inferenceThread = new AtomicReference<>();
        AtomicReference<Thread> releaseThread = new AtomicReference<>();
        AiModel model = new AiModel() {
            @Override
            public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                calls.incrementAndGet();
                inferenceThread.set(Thread.currentThread());
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release model");
                return AiDecision.of(AiDecision.Verdict.RISK);
            }

            @Override
            public void close() {
                releaseThread.set(Thread.currentThread());
                modelClosed.countDown();
            }
        };
        AiAnalysisRunner[] runner = new AiAnalysisRunner[1];
        try {
            onMain(() -> {
                runner[0] = new AiAnalysisRunner(context, () -> model, 5_000);
                runner[0].check(matching(), new AiAnalysisRunner.Callback() {
                    @Override public void onProgress(int completed, int total) { }
                    @Override public void onResult(AnalysisReport report) { callbacks.incrementAndGet(); }
                });
            });
            assertTrue("The first evaluation never started", entered.await(5, TimeUnit.SECONDS));

            onMain(() -> runner[0].cancel());
            release.countDown();
            assertTrue("The cancelled model was not released", modelClosed.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertEquals("Cancellation must stop before the second pair", 1, calls.get());
            assertEquals("A cancelled scan must not deliver a late report", 0, callbacks.get());
            assertSame("Native model must be closed on its inference worker", inferenceThread.get(), releaseThread.get());
            assertNotSame(Looper.getMainLooper().getThread(), releaseThread.get());
        } finally {
            release.countDown();
            onMain(() -> { if (runner[0] != null) runner[0].close(); });
        }
    }

    @Test
    public void timeoutRetainsCompletedRiskAndRemainingUncheckedIngredients() throws Exception {
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch firstProgress = new CountDownLatch(1);
        CountDownLatch resultDelivered = new CountDownLatch(1);
        CountDownLatch modelClosed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger results = new AtomicInteger();
        AtomicBoolean mainCallbacks = new AtomicBoolean(true);
        AtomicReference<AnalysisReport> result = new AtomicReference<>();
        AiModel model = new AiModel() {
            @Override
            public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                int request = calls.incrementAndGet();
                if (request == 1) return AiDecision.of(AiDecision.Verdict.RISK);
                secondEntered.countDown();
                if (!releaseSecond.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release model");
                return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
            }

            @Override public void close() { modelClosed.countDown(); }
        };
        AiAnalysisRunner[] runner = new AiAnalysisRunner[1];
        try {
            onMain(() -> {
                runner[0] = new AiAnalysisRunner(context, () -> model, 1_000);
                runner[0].check(matching(), new AiAnalysisRunner.Callback() {
                    @Override
                    public void onProgress(int completed, int total) {
                        if (Looper.myLooper() != Looper.getMainLooper()) mainCallbacks.set(false);
                        if (completed == 1) firstProgress.countDown();
                    }

                    @Override
                    public void onResult(AnalysisReport report) {
                        if (Looper.myLooper() != Looper.getMainLooper()) mainCallbacks.set(false);
                        results.incrementAndGet();
                        result.set(report);
                        resultDelivered.countDown();
                    }
                });
            });
            assertTrue("The first completed check was not reported", firstProgress.await(5, TimeUnit.SECONDS));
            assertTrue("The second evaluation never started", secondEntered.await(5, TimeUnit.SECONDS));
            assertTrue("The blocked request did not time out", resultDelivered.await(5, TimeUnit.SECONDS));

            AnalysisReport timedOut = result.get();
            assertTrue(timedOut.aiAnalysis());
            assertTrue(timedOut.aiIncomplete());
            assertEquals(AnalysisReport.Status.POSSIBLE, timedOut.overall());
            assertTrue(hasFinding(timedOut, Finding.Reason.AI_RISK, "mystery protein"));
            assertTrue(hasFinding(timedOut, Finding.Reason.AI_UNAVAILABLE, "sugar"));
            assertTrue(hasFinding(timedOut, Finding.Reason.AI_UNAVAILABLE, "salt"));
            assertTrue(mainCallbacks.get());

            // A native call can finish after timeout. It must neither start salt nor replace
            // the already delivered timeout report with its late negative answer for sugar.
            releaseSecond.countDown();
            assertTrue("The timed-out model was not released", modelClosed.await(5, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(2, calls.get());
            assertEquals(1, results.get());
            assertSame(timedOut, result.get());
        } finally {
            releaseSecond.countDown();
            onMain(() -> { if (runner[0] != null) runner[0].close(); });
        }
    }

    @Test
    public void cloudRunnerUsesConfiguredConcurrentRequestCountAndIndependentClients() throws Exception {
        AnalysisPreferences.saveMode(context, AnalysisPreferences.Mode.DECISIONS);
        AnalysisPreferences.saveCloudConcurrency(context, 2);
        CountDownLatch firstWave = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(3);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicBoolean callbacksOnMain = new AtomicBoolean(true);
        AtomicReference<AnalysisReport> result = new AtomicReference<>();
        AiAnalysisRunner[] runner = new AiAnalysisRunner[1];
        try {
            onMain(() -> {
                runner[0] = new AiAnalysisRunner(context, () -> {
                    factories.incrementAndGet();
                    return new AiModel() {
                        @Override
                        public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                            calls.incrementAndGet();
                            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                            firstWave.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Test did not release cloud requests");
                                }
                                return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
                            } finally {
                                active.decrementAndGet();
                            }
                        }

                        @Override public void close() { closed.countDown(); }
                    };
                }, 5_000);
                runner[0].check(matching(), new AiAnalysisRunner.Callback() {
                    @Override
                    public void onProgress(int completed, int total) {
                        if (Looper.myLooper() != Looper.getMainLooper()) callbacksOnMain.set(false);
                    }

                    @Override
                    public void onResult(AnalysisReport report) {
                        if (Looper.myLooper() != Looper.getMainLooper()) callbacksOnMain.set(false);
                        result.set(report);
                        delivered.countDown();
                    }
                });
            });
            assertTrue("Two cloud requests did not overlap", firstWave.await(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals(2, maximum.get());
            release.countDown();
            assertTrue("Cloud result was not delivered", delivered.await(5, TimeUnit.SECONDS));
            assertTrue("Cloud clients were not closed", closed.await(5, TimeUnit.SECONDS));
            assertEquals(3, calls.get());
            assertEquals(3, factories.get());
            assertEquals(2, maximum.get());
            assertFalse(result.get().aiIncomplete());
            assertTrue(callbacksOnMain.get());
        } finally {
            release.countDown();
            onMain(() -> { if (runner[0] != null) runner[0].close(); });
        }
    }

    @Test
    public void localRunnerRemainsSingleModelOnOneWorkerWhenCloudConcurrencyIsEight() throws Exception {
        AnalysisPreferences.saveCloudConcurrency(context, 8);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Set<Thread> inferenceThreads = ConcurrentHashMap.newKeySet();
        AtomicReference<Thread> releaseThread = new AtomicReference<>();
        AtomicReference<AnalysisReport> result = new AtomicReference<>();
        AiAnalysisRunner[] runner = new AiAnalysisRunner[1];
        try {
            onMain(() -> {
                runner[0] = new AiAnalysisRunner(context, () -> {
                    factories.incrementAndGet();
                    return new AiModel() {
                        @Override
                        public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                            calls.incrementAndGet();
                            inferenceThreads.add(Thread.currentThread());
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test did not release local inference");
                            }
                            return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
                        }

                        @Override
                        public void close() {
                            releaseThread.set(Thread.currentThread());
                            closed.countDown();
                        }
                    };
                }, 5_000);
                runner[0].check(matching(), new AiAnalysisRunner.Callback() {
                    @Override public void onProgress(int completed, int total) { }

                    @Override
                    public void onResult(AnalysisReport report) {
                        result.set(report);
                        delivered.countDown();
                    }
                });
            });
            assertTrue("Local model did not start", entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, factories.get());
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue("Local result was not delivered", delivered.await(5, TimeUnit.SECONDS));
            assertTrue("Local model was not closed", closed.await(5, TimeUnit.SECONDS));
            assertEquals(1, factories.get());
            assertEquals(3, calls.get());
            assertEquals(1, inferenceThreads.size());
            assertTrue(inferenceThreads.contains(releaseThread.get()));
            assertNotSame(Looper.getMainLooper().getThread(), releaseThread.get());
            assertFalse(result.get().aiIncomplete());
        } finally {
            release.countDown();
            onMain(() -> { if (runner[0] != null) runner[0].close(); });
        }
    }

    private AnalysisReport matching() {
        AllergenDictionary dictionary = AllergenDictionaryProvider.get(context);
        return Analyzer.analyse("Ingredients: mystery protein, sugar, salt.", dictionary,
                Collections.singletonList(dictionary.byId("MILK")));
    }

    private static boolean hasFinding(AnalysisReport report, Finding.Reason reason, String ingredient) {
        for (Finding finding : report.results().get(0).findings()) {
            if (finding.reason() == reason && finding.matchedTerm().equals(ingredient)) return true;
        }
        return false;
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
