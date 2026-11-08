package com.example.allergycheck.analysis;

import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AiModel;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Verifies actual overlapping requests, cancellation, and result attribution without network I/O. */
public class AiAnalyzerConcurrencyTest {
    private final AllergenDictionary dictionary = TestDictionaries.shipped();

    @Test
    public void configuredParallelismBoundsActualRequestsIncludingSerialMode() throws Exception {
        assertBoundedRequests(1);
        assertBoundedRequests(3);
        assertBoundedRequests(AiAnalyzer.MAX_CLOUD_CONCURRENCY);
    }

    private void assertBoundedRequests(int limit) throws Exception {
        List<String> ingredients = Arrays.asList("alpha", "beta", "gamma", "delta", "epsilon",
                "zeta", "eta", "theta", "iota", "kappa", "lambda", "omicron");
        AnalysisReport matching = matching("Ingredients: " + String.join(", ", ingredients) + ".", "MILK");
        int expectedParallelism = Math.min(limit, ingredients.size());
        CountDownLatch firstWave = new CountDownLatch(expectedParallelism);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(ingredients.size());
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        Set<AiModel> clients = ConcurrentHashMap.newKeySet();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<AnalysisReport> result = coordinator.submit(() -> AiAnalyzer.analyseConcurrent(
                    matching, false, limit, () -> {
                        AiModel client = new AiModel() {
                            @Override
                            public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                                assertEquals("MILK", allergen.id());
                                assertFalse(ingredient.contains(","));
                                calls.incrementAndGet();
                                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                                firstWave.countDown();
                                try {
                                    await(release, "First request wave was not released");
                                    return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
                                } finally {
                                    active.decrementAndGet();
                                }
                            }

                            @Override public void close() { closed.countDown(); }
                        };
                        assertTrue("Each request must have an independent client", clients.add(client));
                        return client;
                    }, () -> false, (completed, total, partial) -> { }));
            await(firstWave, "Configured request slots did not run concurrently");
            assertEquals(expectedParallelism, calls.get());
            assertEquals(expectedParallelism, maximum.get());
            release.countDown();
            AnalysisReport report = result.get(5, TimeUnit.SECONDS);
            await(closed, "Completed clients were not closed");
            assertEquals(ingredients.size(), calls.get());
            assertEquals(ingredients.size(), clients.size());
            assertTrue(maximum.get() <= limit);
            assertEquals(0, active.get());
            assertFalse(report.aiIncomplete());
        } finally {
            release.countDown();
            coordinator.shutdownNow();
        }
    }

    @Test
    public void cloudBatchesRemainWholeListsForOneAllergenWhileRunningConcurrently() throws Exception {
        List<String> expected = Arrays.asList("mystery protein", "salt", "sugar");
        Set<String> allergens = ConcurrentHashMap.newKeySet();
        CountDownLatch firstWave = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<AnalysisReport> result = coordinator.submit(() -> AiAnalyzer.analyseConcurrent(
                    matching("Ingredients: mystery protein, salt, sugar.", "MILK", "SOY", "EGGS"),
                    true, 2, () -> new AiModel() {
                        @Override public boolean supportsBatch() { return true; }

                        @Override
                        public AiDecision evaluate(String ingredient, Allergen allergen) {
                            throw new AssertionError("Cloud LLM must not send individual ingredient requests");
                        }

                        @Override
                        public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen)
                                throws Exception {
                            assertEquals(expected, ingredients);
                            assertTrue(allergens.add(allergen.id()));
                            calls.incrementAndGet();
                            firstWave.countDown();
                            await(release, "Cloud batches were not released");
                            List<AiDecision> decisions = new ArrayList<>();
                            for (String ingredient : ingredients) {
                                decisions.add(AiDecision.of(allergen.id().equals("MILK")
                                        && ingredient.equals("mystery protein")
                                        ? AiDecision.Verdict.RISK : AiDecision.Verdict.NO_EVIDENCE));
                            }
                            return decisions;
                        }
                    }, () -> false, (completed, total, partial) -> { }));
            await(firstWave, "Two cloud allergen requests did not overlap");
            assertEquals(2, calls.get());
            release.countDown();
            AnalysisReport report = result.get(5, TimeUnit.SECONDS);
            assertEquals(3, calls.get());
            assertEquals(3, allergens.size());
            assertFalse(report.aiIncomplete());
            assertTrue(hasFinding(report, "MILK", Finding.Reason.AI_RISK, "mystery protein"));
        } finally {
            release.countDown();
            coordinator.shutdownNow();
        }
    }

    @Test
    public void outOfOrderCompletionPreservesRiskAndOriginalUncheckedIngredients() throws Exception {
        CountDownLatch alphaEntered = new CountDownLatch(1);
        CountDownLatch releaseAlpha = new CountDownLatch(1);
        CountDownLatch riskReported = new CountDownLatch(1);
        AtomicReference<AnalysisReport> riskSnapshot = new AtomicReference<>();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<AnalysisReport> result = coordinator.submit(() -> AiAnalyzer.analyseConcurrent(
                    matching("Ingredients: alpha, mystery protein, gamma, delta.", "MILK"), false, 2,
                    () -> (ingredient, allergen) -> {
                        if (ingredient.equals("alpha")) {
                            alphaEntered.countDown();
                            await(releaseAlpha, "Slow first request was not released");
                        }
                        if (ingredient.equals("gamma")) throw new IOException("Unavailable");
                        return AiDecision.of(ingredient.equals("mystery protein")
                                ? AiDecision.Verdict.RISK : AiDecision.Verdict.NO_EVIDENCE);
                    }, () -> false, (completed, total, partial) -> {
                        if (hasFinding(partial, "MILK", Finding.Reason.AI_RISK, "mystery protein")
                                && hasFinding(partial, "MILK", Finding.Reason.AI_UNAVAILABLE, "alpha")
                                && riskSnapshot.compareAndSet(null, partial)) {
                            riskReported.countDown();
                        }
                    }));
            await(alphaEntered, "First ingredient did not start");
            await(riskReported, "A later risk was blocked behind the slow first request");
            AnalysisReport snapshot = riskSnapshot.get();
            assertTrue(snapshot.aiIncomplete());
            releaseAlpha.countDown();
            AnalysisReport report = result.get(5, TimeUnit.SECONDS);
            assertTrue(report.aiIncomplete());
            assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
            assertTrue(hasFinding(report, "MILK", Finding.Reason.AI_RISK, "mystery protein"));
            assertTrue(hasFinding(report, "MILK", Finding.Reason.AI_UNAVAILABLE, "gamma"));
            assertFalse(hasFinding(report, "MILK", Finding.Reason.AI_UNAVAILABLE, "alpha"));
            // A partial report handed to a timeout callback must remain a stable snapshot.
            assertTrue(hasFinding(snapshot, "MILK", Finding.Reason.AI_UNAVAILABLE, "alpha"));
        } finally {
            releaseAlpha.countDown();
            coordinator.shutdownNow();
        }
    }

    @Test
    public void cancellationClosesInFlightClientsAndDoesNotSendQueuedIngredients() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch closed = new CountDownLatch(2);
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<AnalysisReport> result = coordinator.submit(() -> AiAnalyzer.analyseConcurrent(
                    matching("Ingredients: alpha, beta, gamma, delta, epsilon.", "MILK"), false, 2,
                    () -> new AiModel() {
                        final AtomicBoolean wasClosed = new AtomicBoolean();
                        final CountDownLatch closeSignal = new CountDownLatch(1);

                        @Override
                        public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                            calls.incrementAndGet();
                            entered.countDown();
                            await(closeSignal, "Cancellation did not close the active client");
                            return AiDecision.of(AiDecision.Verdict.NO_EVIDENCE);
                        }

                        @Override
                        public void close() {
                            if (wasClosed.compareAndSet(false, true)) {
                                closeSignal.countDown();
                                closed.countDown();
                            }
                        }
                    }, cancelled::get, (completed, total, partial) -> { }));
            await(entered, "Initial cloud request slots did not start");
            cancelled.set(true);
            try {
                result.get(5, TimeUnit.SECONDS);
                fail("A cancelled analysis must not return a completed report");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof InterruptedException);
            }
            await(closed, "In-flight clients were not closed on cancellation");
            assertEquals("No additional ingredients may be uploaded after cancellation", 2, calls.get());
        } finally {
            cancelled.set(true);
            coordinator.shutdownNow();
        }
    }

    @Test
    public void individualRequestTimeoutClosesBlockedClientAndRetainsCompletedRisk() throws Exception {
        CountDownLatch firstWave = new CountDownLatch(2);
        CountDownLatch blockedClientClosed = new CountDownLatch(1);
        CountDownLatch riskReported = new CountDownLatch(1);
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<AnalysisReport> result = coordinator.submit(() -> AiAnalyzer.analyseConcurrent(
                    matching("Ingredients: alpha, mystery protein, gamma.", "MILK"), false, 2,
                    () -> new AiModel() {
                        final AtomicBoolean blocked = new AtomicBoolean();
                        final CountDownLatch closeSignal = new CountDownLatch(1);

                        @Override
                        public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
                            firstWave.countDown();
                            if (ingredient.equals("alpha")) {
                                blocked.set(true);
                                // Model a socket read that does not stop merely because its
                                // future is interrupted; close() must release the connection.
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                while (closeSignal.getCount() != 0) {
                                    long remaining = deadline - System.nanoTime();
                                    if (remaining <= 0) throw new IOException("Client was not closed");
                                    try {
                                        closeSignal.await(remaining, TimeUnit.NANOSECONDS);
                                    } catch (InterruptedException ignored) { }
                                }
                            }
                            return AiDecision.of(ingredient.equals("mystery protein")
                                    ? AiDecision.Verdict.RISK : AiDecision.Verdict.NO_EVIDENCE);
                        }

                        @Override
                        public void close() {
                            closeSignal.countDown();
                            if (blocked.get()) blockedClientClosed.countDown();
                        }
                    }, () -> false, (completed, total, partial) -> {
                        if (hasFinding(partial, "MILK", Finding.Reason.AI_RISK, "mystery protein")) {
                            riskReported.countDown();
                        }
                    }, 1_000L));
            await(firstWave, "Concurrent timeout test requests did not start");
            await(riskReported, "Fast cloud risk did not appear before the slow request completed");
            AnalysisReport report = result.get(5, TimeUnit.SECONDS);
            await(blockedClientClosed, "Timed-out HTTP-like client was not closed");
            assertTrue(report.aiIncomplete());
            assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
            assertTrue(hasFinding(report, "MILK", Finding.Reason.AI_RISK, "mystery protein"));
            assertTrue(hasFinding(report, "MILK", Finding.Reason.AI_UNAVAILABLE, "alpha"));
            assertFalse(hasFinding(report, "MILK", Finding.Reason.AI_UNAVAILABLE, "gamma"));
        } finally {
            coordinator.shutdownNow();
        }
    }

    @Test
    public void invalidConcurrencyIsRejectedBeforeCreatingAClient() {
        for (int concurrency : new int[]{Integer.MIN_VALUE, -1, 0,
                AiAnalyzer.MAX_CLOUD_CONCURRENCY + 1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> AiAnalyzer.analyseConcurrent(
                    matching("Ingredients: alpha, beta.", "MILK"), false, concurrency,
                    () -> { throw new AssertionError("Invalid concurrency must not start a request"); },
                    () -> false, (completed, total, partial) -> { }));
        }
    }

    private AnalysisReport matching(String text, String... ids) {
        List<Allergen> selected = new ArrayList<>();
        for (String id : ids) {
            Allergen allergen = dictionary.byId(id);
            assertNotNull(allergen);
            selected.add(allergen);
        }
        return Analyzer.analyse(text, dictionary, selected);
    }

    private static boolean hasFinding(AnalysisReport report, String allergen,
                                      Finding.Reason reason, String ingredient) {
        return report.results().stream().filter(result -> result.allergen().id().equals(allergen))
                .flatMap(result -> result.findings().stream())
                .anyMatch(finding -> finding.reason() == reason && finding.evidence().equals(ingredient));
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(message, latch.await(5, TimeUnit.SECONDS));
    }
}
