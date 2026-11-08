package com.example.allergycheck;

import android.content.res.ColorStateList;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
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
            long now = SystemClock.uptimeMillis();
            apply(stabilizer.refresh(now), now);
            handler.postDelayed(this, REFRESH_INTERVAL_MILLIS);
        }
    };

    @Nullable
    private FrameReadability.Verdict shown;
    private boolean shownEnabled;
    private boolean shownArmed;
    private boolean frozen;

    CaptureFeedback(@NonNull GuideOverlayView overlay, @NonNull View chip,
            @NonNull ImageView chipIcon, @NonNull MaterialTextView chipText, @NonNull View action,
            @NonNull Runnable captureRequest) {
        this.overlay = overlay;
        this.chip = chip;
        this.chipIcon = chipIcon;
        this.chipText = chipText;
        this.action = action;
        this.captureRequest = captureRequest;
        apply(FrameReadability.Verdict.SEARCHING, SystemClock.uptimeMillis());
    }

    /** Turns auto-capture on or off and reflects it immediately, without waiting for a frame. */
    @MainThread
    void setAutoCaptureEnabled(boolean enabled) {
        gate.setAutoCaptureEnabled(enabled);
        apply(stabilizer.displayed(), SystemClock.uptimeMillis());
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
    public void onAssessment(@NonNull FrameReadability.Assessment assessment,
            @Nullable RectF textBounds, @NonNull List<RectF> lineBounds) {
        if (frozen) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        overlay.setLockTarget(textBounds);
        overlay.setDetectedLines(lineBounds);
        apply(stabilizer.accept(assessment.verdict(), now), now);
    }

    /** Pushes one displayed verdict out to the views, doing nothing if nothing has changed. */
    @MainThread
    private void apply(@NonNull FrameReadability.Verdict verdict, long nowMillis) {
        CaptureGate.Decision decision = gate.update(verdict, nowMillis);

        // The whole decision is compared, not just the verdict: the gate can give up, or an
        // auto-capture countdown can start, while the verdict itself is unchanged, and an
        // early return on the verdict alone would swallow both.
        boolean changed = shown != verdict
                || shownEnabled != decision.captureEnabled()
                || shownArmed != decision.autoCaptureArmed();
        if (changed) {
            shown = verdict;
            shownEnabled = decision.captureEnabled();
            shownArmed = decision.autoCaptureArmed();

            overlay.setVerdict(verdict);
            bindChip(verdict, decision.autoCaptureArmed());

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
     */
    @MainThread
    private void bindChip(@NonNull FrameReadability.Verdict verdict, boolean autoCaptureArmed) {
        Tone tone = toneFor(verdict);
        chip.setBackgroundTintList(ColorStateList.valueOf(tone.container));
        chipIcon.setImageResource(tone.icon);
        chipIcon.setImageTintList(ColorStateList.valueOf(tone.onContainer));
        chipText.setTextColor(tone.onContainer);

        String message = chipText.getResources().getString(
                autoCaptureArmed ? R.string.camera_state_capturing : messageFor(verdict));
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

    @ColorInt
    private int attr(@AttrRes int attribute) {
        TypedValue value = new TypedValue();
        chip.getContext().getTheme().resolveAttribute(attribute, value, true);
        return value.data;
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
