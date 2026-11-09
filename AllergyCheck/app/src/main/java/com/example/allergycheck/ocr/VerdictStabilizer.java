package com.example.allergycheck.ocr;

import androidx.annotation.NonNull;

/**
 * Smooths the per-frame stream of {@link FrameReadability.Verdict}s into something a person can
 * actually read.
 *
 * <p>Raw recogniser output flickers. A hand-held phone over a label produces runs like
 * {@code READY, TOO_FAR, READY, READY, TILTED} within half a second, because one marginal frame
 * changes a median. Mapped straight to the screen that is a strobing message and a shutter button
 * that blinks in and out under the user's thumb — unusable, and it reads as a broken app rather than
 * as a camera being honest about a hard frame.
 *
 * <p>Two rules do the smoothing:
 * <ul>
 *   <li><b>Agreement.</b> A new verdict must turn up on several consecutive frames before it is
 *       shown, so a single odd frame changes nothing.</li>
 *   <li><b>Dwell.</b> A verdict that has just been shown is held for a minimum time, so instructions
 *       stay on screen long enough to be read and obeyed.</li>
 * </ul>
 *
 * <p>The thresholds are <b>deliberately asymmetric</b>: giving up {@link FrameReadability.Verdict#READY}
 * takes more agreement than reaching it. The reason is physical — reaching for the shutter shakes
 * the phone, so the frames taken while a thumb is in flight are the worst of the whole session, and
 * a symmetric rule disables the button at exactly the moment it is being pressed.
 *
 * <p>Pure Java with the clock passed in, so the whole state machine is unit-testable without a
 * device and without waiting in real time.
 */
public final class VerdictStabilizer {

    /** Frames a new verdict must agree on before it is displayed. At ~4 fps this is about 0.5 s. */
    static final int AGREE_FRAMES = 2;

    /** Frames needed to give up READY. More than {@link #AGREE_FRAMES}; see the class note. */
    static final int RELEASE_READY_FRAMES = 3;

    /** How long a displayed verdict is held before another may replace it. */
    static final long MIN_DWELL_MILLIS = 600L;

    /**
     * No frames for this long and the display falls back to
     * {@link FrameReadability.Verdict#SEARCHING}.
     *
     * <p>Without this the screen keeps whatever it last said when frames stop arriving — so a user
     * who lowers the phone, or hits a recogniser stall, is left looking at a stale and now wrong
     * "ready to shoot".
     */
    static final long STALE_MILLIS = 1200L;

    private FrameReadability.Verdict displayed = FrameReadability.Verdict.SEARCHING;
    private FrameReadability.Verdict pending = FrameReadability.Verdict.SEARCHING;
    private int pendingCount;
    private long displayedAtMillis = Long.MIN_VALUE;
    private long lastFrameAtMillis = Long.MIN_VALUE;

    /**
     * Feeds in one frame's raw verdict and returns what should be displayed now.
     *
     * @param nowMillis a monotonic clock reading; {@code SystemClock.uptimeMillis()} in the app
     */
    @NonNull
    public FrameReadability.Verdict accept(@NonNull FrameReadability.Verdict raw, long nowMillis) {
        lastFrameAtMillis = nowMillis;
        if (raw == displayed) {
            // Already on screen: clear any half-built challenger so a brief wobble back and forth
            // does not accumulate towards a change.
            pending = raw;
            pendingCount = 0;
            return displayed;
        }
        if (raw != pending) {
            pending = raw;
            pendingCount = 0;
        }
        pendingCount++;

        int needed = displayed.isReady() ? RELEASE_READY_FRAMES : AGREE_FRAMES;
        if (pendingCount < needed) {
            return displayed;
        }
        if (displayedAtMillis != Long.MIN_VALUE && nowMillis - displayedAtMillis < MIN_DWELL_MILLIS) {
            // Agreed, but the current message has not been up long enough to have been read. Hold
            // the count so it commits on the next frame past the dwell rather than starting over.
            return displayed;
        }
        return commit(raw, nowMillis);
    }

    /**
     * Call periodically from the UI. Returns what should be displayed now, dropping to
     * {@link FrameReadability.Verdict#SEARCHING} once frames have stopped arriving.
     */
    @NonNull
    public FrameReadability.Verdict refresh(long nowMillis) {
        if (lastFrameAtMillis != Long.MIN_VALUE && nowMillis - lastFrameAtMillis > STALE_MILLIS
                && displayed != FrameReadability.Verdict.SEARCHING) {
            return commit(FrameReadability.Verdict.SEARCHING, nowMillis);
        }
        return displayed;
    }

    /** What is on screen right now. */
    @NonNull
    public FrameReadability.Verdict displayed() {
        return displayed;
    }

    @NonNull
    private FrameReadability.Verdict commit(@NonNull FrameReadability.Verdict verdict,
            long nowMillis) {
        displayed = verdict;
        pending = verdict;
        pendingCount = 0;
        displayedAtMillis = nowMillis;
        return displayed;
    }
}
