package com.example.allergycheck;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.ai.AiModel;
import com.example.allergycheck.analysis.ai.LocalAiModel;
import com.example.allergycheck.analysis.ai.OpenRouterAiModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns one screen's AI work. Native inference and model release always use the same worker. */
final class AiAnalysisRunner {
    /** Test seam for lifecycle checks without a live model, credentials, or network. */
    interface ModelFactory {
        AiModel create() throws Exception;
    }

    interface Callback {
        void onProgress(int completed, int total);
        void onResult(AnalysisReport report);
    }

    private static final long PAIR_TIMEOUT_MS = 90_000L;
    private final Context context;
    private final ModelFactory modelFactory;
    private final long pairTimeoutMs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Job current;
    private boolean closed;

    AiAnalysisRunner(Context context) {
        this(context, null, PAIR_TIMEOUT_MS);
    }

    AiAnalysisRunner(Context context, ModelFactory modelFactory, long pairTimeoutMs) {
        if (pairTimeoutMs <= 0) {
            throw new IllegalArgumentException("The AI inactivity timeout must be positive");
        }
        this.context = context.getApplicationContext();
        this.modelFactory = modelFactory;
        this.pairTimeoutMs = pairTimeoutMs;
    }

    /** Called on the main thread, taking a configuration snapshot for this scan. */
    void check(AnalysisReport matching, Callback callback) {
        cancel();
        if (closed) {
            return;
        }
        AnalysisPreferences.Mode mode = AnalysisPreferences.mode(context);
        String apiKey = AnalysisPreferences.openRouterApiKey(context);
        String modelId = mode == AnalysisPreferences.Mode.DECISIONS
                ? AnalysisPreferences.decisionsModel(context)
                : AnalysisPreferences.openRouterModel(context);
        double threshold = AnalysisPreferences.decisionsThreshold(context);
        int concurrency = AnalysisPreferences.cloudConcurrency(context);
        boolean cloud = mode == AnalysisPreferences.Mode.OPENROUTER
                || mode == AnalysisPreferences.Mode.DECISIONS;
        Job job = new Job(AiAnalyzer.incompleteReport(matching), callback);
        current = job;
        // The timer starts when the worker reaches this job, not now: a cancelled scan's native
        // generation cannot be interrupted and would otherwise eat this scan's budget. While that
        // old call drains this job has no timer; it is bounded only by the model, and no answer
        // could arrive sooner anyway. Model loading happens after the first progress, so it is timed.
        worker.execute(() -> {
            if (job.cancelled) {
                return;
            }
            main.post(() -> {
                if (!job.cancelled && !job.answered && !closed && current == job) {
                    main.removeCallbacks(job.timeout);
                    main.postDelayed(job.timeout, pairTimeoutMs);
                }
            });
            AiModel model = null;
            try {
                AiAnalyzer.ProgressListener progress = (completed, total, partial) -> main.post(() -> {
                            if (!job.cancelled && !job.answered) {
                                job.latest = partial;
                                main.removeCallbacks(job.timeout);
                                main.postDelayed(job.timeout, pairTimeoutMs);
                                callback.onProgress(completed, total);
                            }
                        });
                AnalysisReport result;
                if (cloud) {
                    // Each in-flight HTTP request owns its client/connection; a local native
                    // engine is never shared or moved onto these concurrent request workers.
                    result = AiAnalyzer.analyseConcurrent(matching,
                            mode == AnalysisPreferences.Mode.OPENROUTER, concurrency,
                            () -> modelFactory != null ? modelFactory.create()
                                    : mode == AnalysisPreferences.Mode.DECISIONS
                                    ? new OpenRouterAiModel(apiKey, modelId, threshold)
                                    : new OpenRouterAiModel(apiKey, modelId),
                            () -> job.cancelled, progress, pairTimeoutMs);
                } else {
                    model = modelFactory != null ? modelFactory.create() : new LocalAiModel(context);
                    result = AiAnalyzer.analyse(matching, model, () -> job.cancelled, progress);
                }
                main.post(() -> job.finish(result));
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Never log label text, model replies, or credentials. Keep the best partial result.
                main.post(() -> job.finish(job.latest.withAiFailure(
                        AnalysisReport.AiFailure.UNAVAILABLE, 0)));
            } finally {
                if (model != null) {
                    try {
                        model.close();
                    } catch (Exception | LinkageError ignored) {
                        // The already delivered report remains usable if native release fails.
                    }
                }
            }
        });
    }

    void cancel() {
        if (current != null) {
            current.cancelled = true;
            main.removeCallbacks(current.timeout);
            current = null;
        }
    }

    void close() {
        cancel();
        closed = true;
        // Running native generation cannot be interrupted safely. Its finally releases the model.
        worker.shutdown();
    }

    private final class Job {
        final Callback callback;
        AnalysisReport latest;
        final Runnable timeout = () -> finish(latest, true);
        volatile boolean cancelled;
        boolean answered;

        Job(AnalysisReport initial, Callback callback) {
            this.latest = initial;
            this.callback = callback;
        }

        void finish(AnalysisReport report) {
            finish(report, false);
        }

        private void finish(AnalysisReport report, boolean timedOut) {
            if (!cancelled && !answered && !closed && current == this) {
                // Set this before invoking app code. A callback may unblock the current native
                // request; it must already see cancellation before attempting another pair.
                if (timedOut) cancelled = true;
                answered = true;
                main.removeCallbacks(timeout);
                callback.onResult(timedOut
                        ? report.withAiFailure(AnalysisReport.AiFailure.TIMEOUT, 0) : report);
            }
        }
    }
}
