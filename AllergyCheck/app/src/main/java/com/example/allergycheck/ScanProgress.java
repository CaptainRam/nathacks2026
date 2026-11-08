package com.example.allergycheck;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * Fills the check's determinate Material 3 circular indicator across every step of a scan, in every
 * detection mode.
 *
 * <p>Each step owns a slice of one 0-100 ring. Matching alone spends the whole ring on reading the
 * label; the AI modes keep reading short and give most of the ring to the AI checks, which report
 * real counts. Steps with no inner progress of their own (decoding, recognising, loading a model)
 * creep slowly toward the end of their slice so the ring never looks stalled, but never reach it:
 * only the next real step moves past. Before the first AI check reports, the ring only creeps a
 * little into the checks' slice. The ring never goes backwards within a scan.
 */
final class ScanProgress {
    enum Step { DECODING, PREPARING, RECOGNIZING, MATCHING, AI_CHECKS }

    static final int MAX = 100;

    /** Where each {@link Step} starts, then where the last one ends, for the matching-only ring. */
    private static final int[] MATCHING_BOUNDS = {0, 20, 40, 90, 100, 100};
    /** The same for the AI modes: reading takes the first 30%, the checks the rest. */
    private static final int[] AI_BOUNDS = {0, 6, 12, 28, 30, 100};
    /** Rough time each step takes, so the creep moves at a believable pace. */
    private static final long[] EXPECTED_MS = {1200, 800, 1500, 300, 4000};
    /** How far into a slice the creep may go before the real step has to take over. */
    private static final float CREEP_SHARE = 0.85f;
    /** Shortest and longest time the ring takes to close once the scan is done. */
    private static final long FINISH_MIN_MS = 250;
    private static final long FINISH_MAX_MS = 600;
    /** How long the ring takes to catch up to a step that starts ahead of it. */
    private static final long CATCH_UP_MS = 300;
    /**
     * Before the first AI check reports (a model loading, or a previous native run finishing) the
     * ring only creeps this far into the checks' slice, leaving the rest for the real counts.
     */
    private static final int AI_WARM_UP_SHARE_PERCENT = 10;

    private final CircularProgressIndicator indicator;
    private AnimatorSet motion;
    private boolean ai;
    private int shown;

    ScanProgress(@NonNull CircularProgressIndicator indicator) {
        this.indicator = indicator;
        indicator.setIndeterminate(false);
        indicator.setMax(MAX);
    }

    /** Starts a new scan at an empty ring; {@code ai} picks how the ring is shared out. */
    @MainThread
    void reset(boolean ai) {
        cancel();
        this.ai = ai;
        shown = 0;
        indicator.setProgressCompat(0, false);
    }

    /** Whether this scan's ring was shared out for an AI mode. */
    boolean isAi() {
        return ai;
    }

    /** Moves to the start of {@code step} and creeps toward its end. */
    @MainThread
    void step(@NonNull Step step) {
        int from = start(step, ai);
        int to = step == Step.AI_CHECKS
                ? from + (end(step, ai) - from) * AI_WARM_UP_SHARE_PERCENT / 100
                : end(step, ai);
        advance(from, to, EXPECTED_MS[step.ordinal()]);
    }

    /** Shows {@code completed} of {@code total} AI checks done and creeps toward the next one. */
    @MainThread
    void aiChecks(int completed, int total) {
        advance(aiValue(completed, total), aiValue(completed + 1, total),
                EXPECTED_MS[Step.AI_CHECKS.ordinal()]);
    }

    /**
     * Closes the ring, then runs {@code onFull}, so a result never replaces a part-filled ring.
     * The rest of the ring fills faster the less there is left. A {@link #cancel()} or
     * {@link #reset(boolean)} before the ring is full drops {@code onFull}; it never runs late.
     */
    @MainThread
    void finish(@NonNull Runnable onFull) {
        cancel();
        if (shown >= MAX || !ValueAnimator.areAnimatorsEnabled()) {
            set(MAX);
            onFull.run();
            return;
        }
        AnimatorSet set = new AnimatorSet();
        long duration = Math.min(FINISH_MAX_MS,
                FINISH_MIN_MS + (FINISH_MAX_MS - FINISH_MIN_MS) * (MAX - shown) / MAX);
        set.play(leg(shown, MAX, duration, 1f, set));
        set.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // cancel() clears motion before cancelling, and a cancel also ends the set.
                if (!cancelled && motion == set) {
                    motion = null;
                    onFull.run();
                }
            }
        });
        motion = set;
        set.start();
    }

    /** Stops the creep; the ring keeps whatever it shows. Safe to call more than once. */
    @MainThread
    void cancel() {
        if (motion != null) {
            AnimatorSet running = motion;
            motion = null;
            running.cancel();
        }
    }

    static int start(@NonNull Step step, boolean ai) {
        return (ai ? AI_BOUNDS : MATCHING_BOUNDS)[step.ordinal()];
    }

    static int end(@NonNull Step step, boolean ai) {
        int[] bounds = ai ? AI_BOUNDS : MATCHING_BOUNDS;
        // Matching-only scans never run AI checks, so their last real step closes the ring.
        return step == Step.MATCHING && !ai ? MAX : bounds[step.ordinal() + 1];
    }

    /** Where the ring stands once {@code completed} of {@code total} AI checks are done. */
    static int aiValue(int completed, int total) {
        int from = start(Step.AI_CHECKS, true);
        int span = end(Step.AI_CHECKS, true) - from;
        if (total <= 0) {
            return from;
        }
        int done = Math.max(0, Math.min(completed, total));
        return from + (int) ((long) span * done / total);
    }

    /**
     * Catches the ring up to {@code from}, then creeps it toward {@code to}. Both legs are driven
     * here frame by frame rather than with the indicator's own animated setter: mixing the two
     * would let each creep frame cut the indicator's spring short, so the ring would snap.
     */
    private void advance(int from, int to, long expectedMs) {
        cancel();
        int target = Math.max(shown, from);
        int creepTo = target + Math.round((to - target) * CREEP_SHARE);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            set(target);
            return;
        }
        AnimatorSet set = new AnimatorSet();
        ValueAnimator catchUp = leg(shown, target, CATCH_UP_MS, 1f, set);
        if (creepTo > target) {
            set.playSequentially(catchUp, leg(target, creepTo, expectedMs, 1.5f, set));
        } else {
            set.play(catchUp);
        }
        motion = set;
        set.start();
    }

    private ValueAnimator leg(int from, int to, long durationMs, float easing, AnimatorSet owner) {
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(from == to ? 0 : durationMs);
        animator.setInterpolator(new DecelerateInterpolator(easing));
        animator.addUpdateListener(animation -> {
            if (motion == owner) {
                set((int) animation.getAnimatedValue());
            }
        });
        return animator;
    }

    private void set(int value) {
        int clamped = Math.min(MAX, value);
        if (clamped < shown) {
            return;
        }
        shown = clamped;
        indicator.setProgressCompat(clamped, false);
    }
}
