package com.example.allergycheck.analysis;

import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.analysis.ai.AiModel;
import com.example.allergycheck.analysis.ai.OpenRouterAiModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Adds per-allergen AI checks without letting a model erase matching evidence. */
public final class AiAnalyzer {
    /** Each cloud request owns a separate client so connections are never shared concurrently. */
    public interface ModelFactory {
        AiModel create() throws Exception;
    }

    public interface ProgressListener {
        /** The immutable partial report includes all ingredients still awaiting a check. */
        void onProgress(int completed, int total, AnalysisReport partial);
    }

    private AiAnalyzer() { }

    public static AnalysisReport analyse(AnalysisReport matching, AiModel model)
            throws InterruptedException {
        return analyse(matching, model, () -> false, (completed, total, partial) -> { });
    }

    /**
     * Every request concerns one selected allergen. Local AI and Decisions check one ingredient
     * per request; a cloud LLM checks the complete ingredient list in one request per allergen.
     * Returned decisions are mapped to the app's original ingredients, never generated names.
     */
    public static AnalysisReport analyse(AnalysisReport matching, AiModel model,
                                         BooleanSupplier cancelled, ProgressListener progress)
            throws InterruptedException {
        Map<String, Check> checks = checksFor(matching);
        progress.onProgress(0, checks.size(), report(matching, checks));
        boolean batch = model.supportsBatch();
        int completed = 0;
        int consecutiveFailures = 0;
        for (List<Check> request : requestsFor(checks, batch)) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("AI check cancelled");
            }
            boolean permanent = false;
            try {
                apply(request, evaluate(request, model, batch));
                consecutiveFailures = 0;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception | LinkageError failure) {
                // Failures stay attached to the input ingredient. A failed check is never clear.
                consecutiveFailures++;
                permanent = isPermanent(failure);
            }
            completed += request.size();
            progress.onProgress(completed, checks.size(), report(matching, checks));
            // Avoid hammering an unavailable model or API. Remaining checks stay visibly unchecked.
            if (permanent || consecutiveFailures >= 3) {
                break;
            }
        }
        return report(matching, checks);
    }

    public static AnalysisReport analyseConcurrent(AnalysisReport matching, boolean batch,
                                                   int concurrency, ModelFactory factory,
                                                   BooleanSupplier cancelled, ProgressListener progress)
            throws InterruptedException {
        return analyseConcurrent(matching, batch, concurrency, factory, cancelled, progress, 90_000L);
    }

    /**
     * Cloud-only bounded scheduling. Local native inference uses the serial overload above.
     * Workers return decisions; only this coordinator applies them and emits immutable reports.
     * The deadline belongs to each request, so other completions cannot keep a stuck request alive.
     */
    public static AnalysisReport analyseConcurrent(AnalysisReport matching, boolean batch,
                                                   int concurrency, ModelFactory factory,
                                                   BooleanSupplier cancelled, ProgressListener progress,
                                                   long requestTimeoutMs) throws InterruptedException {
        if (concurrency < 1 || concurrency > 8 || requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("Cloud concurrency must be 1–8 and timeout positive");
        }
        Map<String, Check> checks = checksFor(matching);
        List<List<Check>> requests = requestsFor(checks, batch);
        progress.onProgress(0, checks.size(), report(matching, checks));
        if (requests.isEmpty()) {
            return report(matching, checks);
        }
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(concurrency, requests.size()));
        CompletionService<RequestResult> completions = new ExecutorCompletionService<>(workers);
        Map<Future<RequestResult>, CloudRequest> active = new LinkedHashMap<>();
        AtomicBoolean stopSubmitting = new AtomicBoolean();
        int next = 0;
        int completed = 0;
        int consecutiveFailures = 0;
        try {
            while (next < requests.size() || !active.isEmpty()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("AI check cancelled");
                }
                while (!stopSubmitting.get() && next < requests.size() && active.size() < concurrency) {
                    if (cancelled.getAsBoolean()) {
                        throw new InterruptedException("AI check cancelled");
                    }
                    CloudRequest request = new CloudRequest(requests.get(next++));
                    Future<RequestResult> future = completions.submit(() -> {
                        try {
                            if (request.abandoned.get() || cancelled.getAsBoolean()) {
                                throw new InterruptedException("AI check cancelled");
                            }
                            request.setModel(factory.create());
                            if (request.abandoned.get() || cancelled.getAsBoolean()
                                    || Thread.currentThread().isInterrupted()) {
                                throw new InterruptedException("AI check cancelled");
                            }
                            return new RequestResult(evaluate(request.checks, request.model(), batch), null);
                        } catch (Exception | LinkageError failure) {
                            if (isPermanent(failure)) stopSubmitting.set(true);
                            return new RequestResult(null, failure);
                        } finally {
                            request.closeModel();
                        }
                    });
                    active.put(future, request);
                }
                if (active.isEmpty()) {
                    break;
                }
                Future<RequestResult> done = completions.poll(100, TimeUnit.MILLISECONDS);
                CloudRequest finished = done == null ? null : active.remove(done);
                if (finished != null) {
                    RequestResult outcome;
                    try {
                        outcome = done.get();
                    } catch (ExecutionException | CancellationException failure) {
                        outcome = new RequestResult(null, failure);
                    }
                    if (outcome.failure == null) {
                        apply(finished.checks, outcome.decisions);
                        consecutiveFailures = 0;
                    } else {
                        consecutiveFailures++;
                        if (isPermanent(outcome.failure) || consecutiveFailures >= 3) {
                            stopSubmitting.set(true);
                        }
                    }
                    completed += finished.checks.size();
                    progress.onProgress(completed, checks.size(), report(matching, checks));
                }
                long now = System.nanoTime();
                for (Map.Entry<Future<RequestResult>, CloudRequest> entry
                        : new ArrayList<>(active.entrySet())) {
                    if (!entry.getKey().isDone() && now - entry.getValue().submittedAt
                            >= TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs)) {
                        CloudRequest expired = active.remove(entry.getKey());
                        expired.cancel(entry.getKey());
                        consecutiveFailures++;
                        if (consecutiveFailures >= 3) stopSubmitting.set(true);
                        completed += expired.checks.size();
                        progress.onProgress(completed, checks.size(), report(matching, checks));
                    }
                }
            }
            return report(matching, checks);
        } finally {
            for (Map.Entry<Future<RequestResult>, CloudRequest> entry : active.entrySet()) {
                entry.getValue().cancel(entry.getKey());
            }
            workers.shutdownNow();
        }
    }

    private static List<AiDecision> evaluate(List<Check> request, AiModel model, boolean batch)
            throws Exception {
        if (batch) {
            List<String> ingredients = new ArrayList<>();
            for (Check check : request) ingredients.add(check.ingredient);
            List<AiDecision> decisions = model.evaluateBatch(
                    Collections.unmodifiableList(ingredients), request.get(0).allergen);
            if (decisions == null || decisions.size() != request.size()) {
                throw new IllegalStateException("The AI batch did not align with its ingredients");
            }
            return new ArrayList<>(decisions);
        }
        Check check = request.get(0);
        AiDecision decision = model.evaluate(check.ingredient, check.allergen);
        if (decision == null) throw new IllegalStateException("No AI decision returned");
        return Collections.singletonList(decision);
    }

    private static void apply(List<Check> request, List<AiDecision> decisions) {
        for (int i = 0; i < request.size(); i++) {
            // A missing verdict stays unchecked, while valid risks are retained.
            request.get(i).decision = decisions.get(i);
        }
    }

    private static boolean isPermanent(Throwable failure) {
        return failure instanceof OpenRouterAiModel.ApiException
                && ((OpenRouterAiModel.ApiException) failure).permanent();
    }

    private static final class RequestResult {
        final List<AiDecision> decisions;
        final Throwable failure;

        RequestResult(List<AiDecision> decisions, Throwable failure) {
            this.decisions = decisions;
            this.failure = failure;
        }
    }

    private static final class CloudRequest {
        final List<Check> checks;
        final long submittedAt = System.nanoTime();
        final AtomicBoolean abandoned = new AtomicBoolean();
        private AiModel model;

        CloudRequest(List<Check> checks) {
            this.checks = checks;
        }

        void setModel(AiModel created) {
            synchronized (this) {
                if (!abandoned.get()) {
                    model = created;
                    return;
                }
            }
            close(created);
        }

        synchronized AiModel model() throws InterruptedException {
            if (model == null) throw new InterruptedException("AI check cancelled");
            return model;
        }

        void closeModel() {
            AiModel closing;
            synchronized (this) {
                closing = model;
                model = null;
            }
            close(closing);
        }

        private static void close(AiModel closing) {
            if (closing != null) {
                try {
                    closing.close();
                } catch (Exception | LinkageError ignored) {
                    // A release failure must not discard a completed report.
                }
            }
        }

        void cancel(Future<?> future) {
            abandoned.set(true);
            future.cancel(true);
            closeModel();
        }
    }

    private static List<List<Check>> requestsFor(Map<String, Check> checks, boolean batch) {
        List<List<Check>> requests = new ArrayList<>();
        if (!batch) {
            for (Check check : checks.values()) {
                requests.add(Collections.singletonList(check));
            }
        } else {
            Map<Allergen, List<Check>> perAllergen = new LinkedHashMap<>();
            for (Check check : checks.values()) {
                perAllergen.computeIfAbsent(check.allergen, ignored -> new ArrayList<>()).add(check);
            }
            requests.addAll(perAllergen.values());
        }
        return requests;
    }

    /** For missing configuration, model load failures, or a timeout before the first reply. */
    public static AnalysisReport incompleteReport(AnalysisReport matching) {
        return report(matching, checksFor(matching));
    }

    private static Map<String, Check> checksFor(AnalysisReport matching) {
        Map<String, Check> checks = new LinkedHashMap<>();
        for (AllergenResult result : matching.results()) {
            Set<String> matched = matchedIngredients(result);
            for (IngredientItem item : matching.label().ingredients()) {
                String ingredient = item.matchText().trim();
                if (!ingredient.isEmpty() && !matched.contains(item.original())) {
                    checks.putIfAbsent(key(result.allergen(), ingredient),
                            new Check(ingredient, result.allergen()));
                }
            }
        }
        return checks;
    }

    /**
     * Ingredients the dictionary already flagged for this allergen. An AI verdict there can only
     * repeat existing evidence (inference never removes a match), so it is not worth a request.
     */
    private static Set<String> matchedIngredients(AllergenResult result) {
        Set<String> matched = new HashSet<>();
        for (Finding finding : result.findings()) {
            if (finding.source() == Finding.Source.INGREDIENT) {
                matched.add(finding.evidence());
            }
        }
        return matched;
    }

    private static String key(Allergen allergen, String ingredient) {
        return allergen.id() + "\u0000" + ingredient;
    }

    private static AnalysisReport report(AnalysisReport matching, Map<String, Check> checks) {
        if (matching.results().isEmpty()) {
            return matching;
        }
        boolean noIngredients = matching.label().ingredients().isEmpty();
        boolean incomplete = noIngredients;
        List<AllergenResult> results = new ArrayList<>();
        for (AllergenResult base : matching.results()) {
            List<Finding> findings = new ArrayList<>(base.findings());
            Set<String> matched = matchedIngredients(base);
            boolean allergenIncomplete = noIngredients;
            for (IngredientItem item : matching.label().ingredients()) {
                if (matched.contains(item.original())) {
                    continue;
                }
                Check check = checks.get(key(base.allergen(), item.matchText().trim()));
                AiDecision decision = check == null ? null : check.decision;
                Finding.Reason reason;
                if (decision == null) {
                    reason = Finding.Reason.AI_UNAVAILABLE;
                    allergenIncomplete = true;
                } else if (decision.verdict() == AiDecision.Verdict.RISK) {
                    reason = Finding.Reason.AI_RISK;
                } else if (decision.verdict() == AiDecision.Verdict.UNCERTAIN) {
                    reason = Finding.Reason.AI_UNCERTAIN;
                } else {
                    continue;
                }
                findings.add(new Finding(base.allergen(), Finding.Level.POSSIBLE,
                        Finding.Source.INGREDIENT, reason, item.matchText(), item.original(),
                        decision == null ? null : decision.score()));
            }
            incomplete |= allergenIncomplete;
            findings.sort((a, b) -> a.level().compareTo(b.level()));
            AnalysisReport.Status status = base.status();
            for (Finding finding : findings) {
                if (finding.reason() == Finding.Reason.AI_UNAVAILABLE) {
                    continue;
                }
                AnalysisReport.Status candidate = finding.level() == Finding.Level.CONTAINS
                        ? AnalysisReport.Status.CONTAINS
                        : finding.level() == Finding.Level.MAY_CONTAIN
                        ? AnalysisReport.Status.MAY_CONTAIN : AnalysisReport.Status.POSSIBLE;
                if (candidate.compareTo(status) < 0) {
                    status = candidate;
                }
            }
            if (allergenIncomplete && status == AnalysisReport.Status.NOT_DETECTED) {
                status = AnalysisReport.Status.UNREADABLE;
            }
            results.add(new AllergenResult(base.allergen(), status, findings));
        }
        results.sort((a, b) -> {
            int byStatus = a.status().compareTo(b.status());
            return byStatus != 0 ? byStatus : a.allergen().compareTo(b.allergen());
        });
        AnalysisReport.Status overall = results.get(0).status();
        // A successful negative for one allergen cannot conceal a failed check for another.
        if (incomplete && overall == AnalysisReport.Status.NOT_DETECTED) {
            overall = AnalysisReport.Status.UNREADABLE;
        }
        return new AnalysisReport(overall, results, matching.label(), matching.lowConfidence(),
                true, incomplete);
    }

    private static final class Check {
        final String ingredient;
        final Allergen allergen;
        AiDecision decision;

        Check(String ingredient, Allergen allergen) {
            this.ingredient = ingredient;
            this.allergen = allergen;
        }
    }
}
