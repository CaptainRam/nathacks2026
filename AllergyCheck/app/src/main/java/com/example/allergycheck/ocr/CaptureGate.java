package com.example.allergycheck.ocr;

import androidx.annotation.NonNull;

/**
 * Decides whether the capture screen's button may be pressed, and — when the user has asked for it —
 * when to press it for them.
 *
 * <p>Three rules, deliberately kept apart from the views so they can be tested without a device, the
 * same split {@link FrameReadability} and {@link VerdictStabilizer} use:
 *
 * <ol>
 *   <li><b>Track the verdict.</b> The button is enabled while the frame reads as an ingredient list
 *       and <em>disabled again</em> the moment it stops. An earlier version unlocked after a short
 *       run of bad frames and then latched on for the rest of the screen's life, which meant the
 *       gate stopped doing anything at all a few seconds in.</li>
 *   <li><b>Give up eventually.</b> After {@link #FORCE_ENABLE_MILLIS} on the screen the button is
 *       enabled permanently, whatever the frame says. The verdict is a heuristic over a third-party
 *       recogniser on unknown hardware, and a user whose perfectly good label it keeps mis-reading
 *       must never be locked out of their own camera. Measured as plain wall-clock from
 *       {@link #start} rather than as a run of bad frames, so it is predictable: half a minute on
 *       this screen and the button simply works.</li>
 *   <li><b>Auto-capture.</b> Optional, off unless switched on. Fires once {@code READY} has held for
 *       {@link #AUTO_CAPTURE_DWELL_MILLIS}.</li>
 * </ol>
 *
 * <p><b>Rule 2 never feeds rule 3.</b> Auto-capture fires only on a real {@code READY}, never off the
 * give-up timer. The timer exists to let a <em>person</em> overrule the heuristic; having the app
 * silently photograph a frame it has already judged unreadable would be the app overruling itself,
 * and the result would be a scan that fails after the shutter instead of advice before it.
 *
 * <p>Not thread-safe and not meant to be: every call comes from the main thread.
 */
public final class CaptureGate {

    /** Wall-clock on the capture screen after which the button is enabled regardless of the frame. */
    static final long FORCE_ENABLE_MILLIS = 30_000L;

    /**
     * How long {@code READY} must hold before auto-capture fires.
     *
     * <p>{@code READY} is commonly reached while the phone is still moving into place — the
     * stabiliser confirms the verdict, not the hand. A short dwell past that is long enough for the
     * frame to settle and to give the user a moment to pull away, and short enough that the capture
     * still feels automatic rather than delayed.
     */
    static final long AUTO_CAPTURE_DWELL_MILLIS = 900L;

    /** What the screen should do right now. */
    public static final class Decision {

        private final boolean captureEnabled;
        private final boolean autoCaptureArmed;
        private final boolean captureNow;

        Decision(boolean captureEnabled, boolean autoCaptureArmed, boolean captureNow) {
            this.captureEnabled = captureEnabled;
            this.autoCaptureArmed = autoCaptureArmed;
            this.captureNow = captureNow;
        }

        /** Whether the action button accepts a press. */
        public boolean captureEnabled() {
            return captureEnabled;
        }

        /**
         * Whether a shot is pending: {@code READY} is being held and auto-capture is counting down.
         * The screen says so, so the capture is never a surprise.
         */
        public boolean autoCaptureArmed() {
            return autoCaptureArmed;
        }

        /** True on exactly one update: take the photo now. */
        public boolean captureNow() {
            return captureNow;
        }
    }

    private boolean autoCaptureEnabled;
    private boolean started;
    private boolean stopped;
    private boolean forced;
    private boolean captureFired;
    private long startMillis;
    private long readySinceMillis = Long.MIN_VALUE;

    /** Begins the give-up clock. Call when the camera is bound, not when the screen is created. */
    public void start(long nowMillis) {
        started = true;
        startMillis = nowMillis;
    }

    /**
     * Stops the gate permanently: no later {@link #update} reports {@link Decision#captureNow()}.
     *
     * <p>Called once a capture is under way, so a frame still in flight cannot fire a second shot
     * behind the one already being written.
     */
    public void stop() {
        stopped = true;
    }

    /** Turns auto-capture on or off. Switching off cancels any shot already counting down. */
    public void setAutoCaptureEnabled(boolean enabled) {
        autoCaptureEnabled = enabled;
        if (!enabled) {
            readySinceMillis = Long.MIN_VALUE;
        }
    }

    public boolean isAutoCaptureEnabled() {
        return autoCaptureEnabled;
    }

    /**
     * Folds one displayed verdict and the current time into a decision.
     *
     * @param displayed the verdict actually on screen — the stabilised one, not a raw frame's, so
     *                  the button does not flicker and auto-capture does not fire off a single
     *                  lucky frame
     */
    @NonNull
    public Decision update(@NonNull FrameReadability.Verdict displayed, long nowMillis) {
        boolean ready = displayed.isReady();

        if (started && !forced && nowMillis - startMillis >= FORCE_ENABLE_MILLIS) {
            // Latched: once the gate has given up it stays given up, so the button cannot vanish
            // from under a thumb that is already moving towards it.
            forced = true;
        }

        if (ready && autoCaptureEnabled && !stopped) {
            if (readySinceMillis == Long.MIN_VALUE) {
                readySinceMillis = nowMillis;
            }
        } else {
            // Any lapse out of READY cancels the countdown outright rather than pausing it: the
            // frame that made it ready is gone, and resuming would shoot a frame nobody vouched for.
            readySinceMillis = Long.MIN_VALUE;
        }

        boolean armed = readySinceMillis != Long.MIN_VALUE && !captureFired;
        boolean captureNow = armed && nowMillis - readySinceMillis >= AUTO_CAPTURE_DWELL_MILLIS;
        if (captureNow) {
            captureFired = true;
            armed = false;
        }

        return new Decision(ready || forced, armed, captureNow);
    }
}
