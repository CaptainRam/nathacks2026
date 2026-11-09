package com.example.allergycheck.analysis.ai;

import static org.junit.Assert.*;

import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real client parsing and coordinator recovery, without credentials or network requests. */
public class OpenRouterRecoveryTest {
    private static final String MODEL = "fixture/model";
    private static final String ANSWER = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
            + "\"content\":\"{\\\"results\\\":[{\\\"ingredient_id\\\":0,"
            + "\\\"verdict\\\":\\\"no_evidence\\\"}]}\"}}]}";

    @Test
    public void accountFailuresStopQueuedWorkAndRetainKnownMatchingEvidence() throws Exception {
        int[] statuses = {401, 402, 404};
        AnalysisReport.AiFailure[] expected = {AnalysisReport.AiFailure.AUTHENTICATION,
                AnalysisReport.AiFailure.QUOTA, AnalysisReport.AiFailure.MODEL};
        for (int index = 0; index < statuses.length; index++) {
            AtomicInteger requests = new AtomicInteger();
            AnalysisReport report = analyse(statuses[index], "provider body must stay private", null,
                    requests);
            assertEquals(1, requests.get());
            assertEquals(expected[index], report.aiFailure());
            assertTrue(report.aiIncomplete());
            assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
            assertTrue(report.results().get(0).findings().stream().anyMatch(
                    finding -> "milk".equals(finding.evidence())));
            assertEquals(0, report.aiRetryAtMillis());
        }
    }

    @Test
    public void invalidModel400RequiresTheExactConfiguredModelMessage() throws Exception {
        String[] messages = {MODEL + " is not a valid model ID",
                "other/model is not a valid model ID", "context length exceeded"};
        for (int index = 0; index < messages.length; index++) {
            String body = "{\"error\":{\"code\":400,\"message\":\"" + messages[index] + "\"}}";
            AtomicInteger requests = new AtomicInteger();
            AnalysisReport report = analyse(400, body, null, requests);
            assertEquals(index == 0 ? 1 : 3, requests.get());
            assertEquals(index == 0 ? AnalysisReport.AiFailure.MODEL
                    : AnalysisReport.AiFailure.UNAVAILABLE, report.aiFailure());
        }
        StubConnection connection = new StubConnection(400,
                "{\"error\":{\"code\":400,\"message\":\"" + messages[0] + "\"}}", null);
        OpenRouterAiModel client = client(connection);
        OpenRouterAiModel.ApiException failure = assertThrows(OpenRouterAiModel.ApiException.class,
                () -> client.evaluate("salt", dictionary().byId("MILK")));
        assertTrue(failure.modelConfigurationError());
        assertFalse(failure.getMessage().contains(MODEL));
    }

    @Test(timeout = 5000)
    public void cooldownStopsNewSubmissionsWithoutWaitingOrAutomaticallyRetrying() throws Exception {
        for (int status : new int[] {429, 503, 402}) {
            long before = System.currentTimeMillis();
            AtomicInteger requests = new AtomicInteger();
            AnalysisReport report = analyse(status, "private upstream error", "120", requests);
            assertEquals("A cooldown must stop beyond the current request", 1, requests.get());
            assertEquals(status == 503 ? AnalysisReport.AiFailure.UNAVAILABLE
                    : AnalysisReport.AiFailure.RATE_LIMIT, report.aiFailure());
            assertTrue(report.aiRetryAtMillis() >= before + 120_000);
            assertTrue(report.aiRetryAfterMillis() > 110_000);
            assertTrue(report.aiIncomplete());
        }
        long date = System.currentTimeMillis() + 180_000;
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC));
        AnalysisReport report = analyse(429, "private", header, new AtomicInteger());
        assertTrue(Math.abs(date - report.aiRetryAtMillis()) < 1000);
    }

    @Test
    public void successfulHttpEnvelopeStillPreservesTypedProviderFailureAndCooldown() throws Exception {
        int[] statuses = {401, 429, 503};
        AnalysisReport.AiFailure[] expected = {AnalysisReport.AiFailure.AUTHENTICATION,
                AnalysisReport.AiFailure.RATE_LIMIT, AnalysisReport.AiFailure.UNAVAILABLE};
        for (int index = 0; index < statuses.length; index++) {
            String body = "{\"error\":{\"code\":" + statuses[index]
                    + ",\"message\":\"private provider text\"}}";
            AtomicInteger requests = new AtomicInteger();
            AnalysisReport report = analyse(200, body, index == 0 ? null : "60", requests);
            assertEquals(1, requests.get());
            assertEquals(expected[index], report.aiFailure());
            assertTrue(report.aiIncomplete());
        }
    }

    @Test
    public void concurrentCooldownKeepsInFlightEvidenceWithoutSubmittingQueuedInputs() throws Exception {
        AllergenDictionary dictionary = dictionary();
        AnalysisReport matching = Analyzer.analyse("Ingredients: alpha, beta, gamma, delta.", dictionary,
                Collections.singletonList(dictionary.byId("MILK")));
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(2);
        CountDownLatch allowLimitedResponse = new CountDownLatch(1);
        CountDownLatch limitedRequestClosed = new CountDownLatch(1);
        CountDownLatch allowSuccess = new CountDownLatch(1);
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        long retryAt = System.currentTimeMillis() + 120_000;
        try {
            Future<AnalysisReport> result = coordinator.submit(() -> AiAnalyzer.analyseConcurrent(
                    matching, false, 2, () -> {
                        int request = requests.incrementAndGet();
                        return new AiModel() {
                            @Override public AiDecision evaluate(String ingredient,
                                    com.example.allergycheck.analysis.Allergen allergen) throws Exception {
                                firstWave.countDown();
                                if (request == 1) {
                                    assertTrue(allowLimitedResponse.await(5, TimeUnit.SECONDS));
                                    throw new OpenRouterAiModel.ApiException(429, retryAt);
                                }
                                assertTrue(allowSuccess.await(5, TimeUnit.SECONDS));
                                return AiDecision.of(AiDecision.Verdict.RISK);
                            }
                            @Override public void close() {
                                if (request == 1) limitedRequestClosed.countDown();
                            }
                        };
                    }, () -> false, (completed, total, partial) -> { }));
            assertTrue(firstWave.await(5, TimeUnit.SECONDS));
            allowLimitedResponse.countDown();
            assertTrue(limitedRequestClosed.await(5, TimeUnit.SECONDS));
            allowSuccess.countDown();
            AnalysisReport report = result.get(5, TimeUnit.SECONDS);
            assertEquals(2, requests.get());
            assertTrue(report.aiIncomplete());
            assertEquals(AnalysisReport.AiFailure.RATE_LIMIT, report.aiFailure());
            assertEquals(retryAt, report.aiRetryAtMillis());
            assertTrue(report.results().get(0).findings().stream().anyMatch(
                    finding -> finding.reason()
                            == com.example.allergycheck.analysis.Finding.Reason.AI_RISK));
        } finally {
            allowLimitedResponse.countDown();
            allowSuccess.countDown();
            coordinator.shutdownNow();
        }
    }

    @Test
    public void malformedAnswersStayUncheckedWhileTransientFailureDoesNotPoisonANewRun()
            throws Exception {
        AtomicInteger malformedCalls = new AtomicInteger();
        String malformed = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                + "\"content\":\"not valid JSON\"}}]}";
        AnalysisReport invalid = analyse(200, malformed, null, malformedCalls);
        assertEquals(5, malformedCalls.get());
        assertEquals(AnalysisReport.AiFailure.INVALID_RESPONSE, invalid.aiFailure());
        assertTrue(invalid.aiIncomplete());
        AnalysisReport badEnvelope = analyse(200, "{malformed", null, new AtomicInteger());
        assertEquals(AnalysisReport.AiFailure.INVALID_RESPONSE, badEnvelope.aiFailure());

        AnalysisReport unavailable = analyse(500, "private", null, new AtomicInteger());
        assertEquals(AnalysisReport.AiFailure.UNAVAILABLE, unavailable.aiFailure());
        AnalysisReport recovered = analyse(200, ANSWER, null, new AtomicInteger());
        assertFalse(recovered.aiIncomplete());
        assertEquals(AnalysisReport.AiFailure.NONE, recovered.aiFailure());
        assertEquals(0, recovered.aiRetryAtMillis());
        assertEquals(AnalysisReport.Status.CONTAINS, recovered.overall());
    }

    @Test
    public void savedReportRetainsSafeRecoveryMetadataWithoutProviderText() throws Exception {
        AnalysisReport report = analyse(429, "private provider body", "120", new AtomicInteger());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(report); }
        assertFalse(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
                .contains("private provider body"));
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            AnalysisReport restored = (AnalysisReport) input.readObject();
            assertEquals(report.overall(), restored.overall());
            assertEquals(report.aiFailure(), restored.aiFailure());
            assertEquals(report.aiRetryAtMillis(), restored.aiRetryAtMillis());
            assertTrue(restored.aiIncomplete());
        }
    }

    private static AnalysisReport analyse(int status, String body, String retryAfter,
                                          AtomicInteger requests) throws Exception {
        AllergenDictionary dictionary = dictionary();
        AnalysisReport matching = Analyzer.analyse(
                "Ingredients: milk, alpha, beta, gamma, delta, epsilon.", dictionary,
                Collections.singletonList(dictionary.byId("MILK")));
        return AiAnalyzer.analyseConcurrent(matching, false, 1, () -> {
            requests.incrementAndGet();
            return client(new StubConnection(status, body, retryAfter));
        }, () -> false, (completed, total, partial) -> { });
    }

    private static AllergenDictionary dictionary() throws Exception {
        return AllergenDictionary.load(new StringReader("{\"schemaVersion\":1,\"allergens\":["
                + "{\"id\":\"MILK\",\"label\":\"Milk\",\"terms\":[\"milk\"]}]}"));
    }

    private static OpenRouterAiModel client(StubConnection connection) {
        return new OpenRouterAiModel("fixture-key", MODEL, null, url -> connection);
    }

    private static final class StubConnection extends HttpURLConnection {
        private final byte[] response;
        private final int status;
        private final String retryAfter;

        StubConnection(int status, String response, String retryAfter) throws Exception {
            super(new URL("https://openrouter.ai/"));
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
            this.retryAfter = retryAfter;
        }

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) {
            return "Retry-After".equalsIgnoreCase(name) ? retryAfter : null;
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
