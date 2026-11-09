package com.example.allergycheck;

import android.content.res.ColorStateList;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.example.allergycheck.ocr.CaptureGate;
import com.example.allergycheck.ocr.FrameReadability;
import com.example.allergycheck.ocr.LiveLabelReader;
import com.example.allergycheck.ocr.VerdictStabilizer;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

/**
 * Turns the stream of per-frame verdicts into what the capture screen shows: the guide overlay's
 * state, the status chip on the preview, the action button's availability, and what a screen reader
 * hears.
 *
 * <p>Separated from {@link CameraCaptureActivity} because it is the only part of the live path with
 * real policy in it, and that policy is easier to follow away from camera binding and permissions.
 *
 * <p>The chip is the same {@code bg_pill} treatment the home screen puts on a chosen photo, tinted
 * through the same container/on-container pairs the analysis screen uses for a verdict (see
 * {@code AnalysisResultView.tone}). That is deliberate rather than incidental: the amber a user sees
 * while framing a label is then the same amber they see in the result, and the capture screen reads
 * as part of the app instead of as a borrowed camera UI.
 *
 * <p>Smoothing is delegated to {@link VerdictStabilizer} and the button-gating and auto-capture
 * policy to {@link CaptureGate}; what is left here is the mapping from a decision to views.
 */
final class CaptureFeedback implements LiveLabelReader.Assessor {

    /**
     * How often the stale check runs.
     *
     * <p>Needed because the verdict is otherwise only ever updated by an arriving frame: if analysis
     * stalls, or the user lowers the phone, the screen would keep showing its last message — and the
     * one message that must never go stale is "ready to shoot".
     */
    private static final long REFRESH_INTERVAL_MILLIS = 400L;

    private final GuideOverlayView overlay;
    private final View chip;
    private final ImageView chipIcon;
    private final MaterialTextView chipText;
    private final View action;
    private final VerdictStabilizer stabilizer = new VerdictStabilizer();
    private final CaptureGate gate = new CaptureGate();
    private final Runnable captureRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (frozen) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            apply(stabilizer.refresh(now), now, false);
            // Both guards are needed. The outer one catches a tick already queued when the screen
            // froze; this one catches the re-entrant case, which is the one that actually bit:
            // apply() can run the capture request, which freezes this feedback from inside this very
            // run(), and freeze()'s removeCallbacks cannot cancel a runnable that is mid-execution.
            // Re-arming regardless left the watchdog ticking through the capture, so ~1.2 s into
            // writing the JPEG the stabiliser went stale and the screen reset itself to "point at
            // the ingredient list" — losing the frozen framing the shot was composed on.
            if (!frozen) {
                handler.postDelayed(this, REFRESH_INTERVAL_MILLIS);
            }
        }
    };

    @Nullable
    private FrameReadability.Verdict shown;
    private boolean shownEnabled;
    private boolean shownArmed;
    private boolean shownHeld;
    private boolean frozen;

    /**
     * Whether the last analysed frame's list read cleanly.
     *
     * <p>Held here rather than passed to {@link #apply} by every caller, because the watchdog has no
     * frame of its own and must reuse the last one's answer; re-deriving it would mean inventing
     * evidence on a tick that carries none.
     */
    private boolean cleanRead = true;

    CaptureFeedback(@NonNull GuideOverlayView overlay, @NonNull View chip,
            @NonNull ImageView chipIcon, @NonNull MaterialTextView chipText, @NonNull View action,
            @NonNull Runnable captureRequest) {
        this.overlay = overlay;
        this.chip = chip;
        this.chipIcon = chipIcon;
        this.chipText = chipText;
        this.action = action;
        this.captureRequest = captureRequest;
        apply(FrameReadability.Verdict.SEARCHING, SystemClock.uptimeMillis(), false);
    }

    /** Turns auto-capture on or off and reflects it immediately, without waiting for a frame. */
    @MainThread
    void setAutoCaptureEnabled(boolean enabled) {
        gate.setAutoCaptureEnabled(enabled);
        apply(stabilizer.displayed(), SystemClock.uptimeMillis(), false);
    }

    /** Begins the stale-frame watchdog. Call once the camera is bound. */
    @MainThread
    void start() {
        // The give-up clock starts when the camera is actually live, not when the screen was
        // created, so a slow camera open does not eat into the user's half minute.
        gate.start(SystemClock.uptimeMillis());
        handler.postDelayed(refresh, REFRESH_INTERVAL_MILLIS);
    }

    /**
     * Stops updating the display and releases the watchdog.
     *
     * <p>Also used at the moment of capture, so the overlay freezes on the framing that was actually
     * photographed instead of drifting while the JPEG is written.
     */
    @MainThread
    void freeze() {
        frozen = true;
        gate.stop();
        handler.removeCallbacks(refresh);
    }

    @Override
    @MainThread
    public void onAssessment(@NonNull FrameReadability.Assessment assessment, boolean cleanRead,
            @Nullable RectF textBounds, @NonNull List<RectF> lineBounds) {
        if (frozen) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        overlay.setLockTarget(textBounds);
        overlay.setDetectedLines(lineBounds);
        this.cleanRead = cleanRead;
        apply(stabilizer.accept(assessment.verdict(), now), now, true);
    }

    /**
     * Pushes one displayed verdict out to the views, doing nothing if nothing has changed.
     *
     * @param fromFrame whether a frame has just been analysed. Only then may the gate advance its
     *                  auto-capture dwell: see {@code CaptureGate.refresh}. Passing {@code true}
     *                  from the watchdog would let a stalled recogniser fire the shutter off a
     *                  verdict no frame had renewed in over a second.
     */
    @MainThread
    private void apply(@NonNull FrameReadability.Verdict verdict, long nowMillis,
            boolean fromFrame) {
        CaptureGate.Decision decision = fromFrame
                ? gate.update(verdict, cleanRead, nowMillis)
                : gate.refresh(verdict, nowMillis);

        // The whole decision is compared, not just the verdict: the gate can give up, an
        // auto-capture countdown can start, or a shot can be withheld for a bad read, while the
        // verdict itself is unchanged, and an early return on the verdict alone would swallow them.
        boolean changed = shown != verdict
                || shownEnabled != decision.captureEnabled()
                || shownArmed != decision.autoCaptureArmed()
                || shownHeld != decision.autoCaptureHeld();
        if (changed) {
            shown = verdict;
            shownEnabled = decision.captureEnabled();
            shownArmed = decision.autoCaptureArmed();
            shownHeld = decision.autoCaptureHeld();

            overlay.setVerdict(verdict);
            bindChip(verdict, decision.autoCaptureArmed(), decision.autoCaptureHeld());

            action.setEnabled(decision.captureEnabled());
            action.setContentDescription(action.getResources().getString(
                    decision.captureEnabled() ? R.string.take_photo
                            : R.string.take_photo_not_ready));
        }

        if (decision.captureNow()) {
            // Last, so the screen already shows the state the photo was taken in. The request runs
            // CameraCaptureActivity.capturePhoto, which freezes this feedback and guards re-entry.
            captureRequest.run();
        }
    }

    /**
     * Tints the chip and swaps its icon and copy, mirroring {@code AnalysisResultView.bindVerdict}.
     *
     * <p>While auto-capture is counting down the chip says so rather than repeating the READY copy,
     * keeping the READY tone: the shot is imminent and good, and the user gets a moment to pull away
     * instead of being surprised by a shutter.
     *
     * <p>When the shot is being withheld for a bad read it says that instead — also in the READY
     * tone, since the framing is right and the only thing left is to hold still. Saying nothing
     * would leave the user waiting on a shutter that is not coming, which is exactly the confusion
     * the whole chip exists to prevent.
     */
    @MainThread
    private void bindChip(@NonNull FrameReadability.Verdict verdict, boolean autoCaptureArmed,
            boolean autoCaptureHeld) {
        Tone tone = toneFor(verdict);
        chip.setBackgroundTintList(ColorStateList.valueOf(tone.container));
        chipIcon.setImageResource(tone.icon);
        chipIcon.setImageTintList(ColorStateList.valueOf(tone.onContainer));
        chipText.setTextColor(tone.onContainer);

        int copy = messageFor(verdict);
        if (autoCaptureArmed) {
            copy = R.string.camera_state_capturing;
        } else if (autoCaptureHeld) {
            copy = R.string.camera_state_unclear;
        }
        String message = chipText.getResources().getString(copy);
        chipText.setText(message);
        // Announced rather than declared as a live region: the stabiliser already guarantees a
        // minimum dwell per message, so this fires at a readable pace, and doing it explicitly
        // avoids the duplicate announcements a live region produces alongside setText.
        chipText.announceForAccessibility(message);
    }

    /** Container colour, text/icon colour, and icon for one verdict. */
    private static final class Tone {

        @ColorInt
        final int container;
        @ColorInt
        final int onContainer;
        @DrawableRes
        final int icon;

        Tone(@ColorInt int container, @ColorInt int onContainer, @DrawableRes int icon) {
            this.container = container;
            this.onContainer = onContainer;
            this.icon = icon;
        }
    }

    private Tone toneFor(@NonNull FrameReadability.Verdict verdict) {
        switch (verdict) {
            case READY:
                return new Tone(attr(com.google.android.material.R.attr.colorPrimaryContainer),
                        attr(com.google.android.material.R.attr.colorOnPrimaryContainer),
                        R.drawable.ic_check);
            case SEARCHING:
                // Neutral: nothing is wrong yet, there is simply nothing in frame to judge.
                return new Tone(attr(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                        attr(com.google.android.material.R.attr.colorOnSurface),
                        R.drawable.ic_search);
            default:
                // Every remaining verdict is "something needs changing", and they share the amber
                // the analysis screen uses for a precautionary verdict.
                return new Tone(color(R.color.status_warning_container),
                        color(R.color.status_on_warning_container), R.drawable.ic_warning);
        }
    }

    /**
     * One theme colour attribute, resolved the way the rest of the app resolves them.
     *
     * <p>{@code MaterialColors.getColor} rather than {@code resolveAttribute} plus
     * {@code TypedValue.data}: {@code data} is only a colour when the attribute resolved to a
     * literal one. If it resolves to a {@code ColorStateList} — which a theme overlay or an OEM
     * Material variant is free to do — {@code data} is an index into the string pool, read as an
     * arbitrary ARGB value, and the chip is tinted with garbage and no error anywhere.
     */
    @ColorInt
    private int attr(@AttrRes int attribute) {
        return MaterialColors.getColor(chip, attribute);
    }

    @ColorInt
    private int color(int colorRes) {
        return ContextCompat.getColor(chip.getContext(), colorRes);
    }

    @StringRes
    private static int messageFor(@NonNull FrameReadability.Verdict verdict) {
        switch (verdict) {
            case TOO_FAR:
                return R.string.camera_state_too_far;
            case NO_INGREDIENT_LIST:
                return R.string.camera_state_wrong_panel;
            case HEADING_ONLY:
                return R.string.camera_state_partial;
            case READY:
                return R.string.camera_state_ready;
            case SEARCHING:
            default:
                return R.string.camera_state_searching;
        }
    }
}
