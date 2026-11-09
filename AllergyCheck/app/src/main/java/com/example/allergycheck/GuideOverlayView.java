package com.example.allergycheck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.allergycheck.ocr.FrameReadability;

import java.util.ArrayList;
import java.util.List;

/**
 * The scanning window over the live camera preview: a dimmed surround, corner brackets that lock
 * onto the ingredient list as it is recognised, and highlights on the text that has been read.
 *
 * <p>It is modelled on a cheque scanner rather than on a plain viewfinder grid. The brackets are not
 * decoration — their position reports <em>what the app thinks it is looking at</em>, and their colour
 * reports whether that is good enough to shoot: neutral while searching, amber while something needs
 * changing, green when the list reads. A user can therefore correct their aim from the frame alone,
 * before reading a word of the hint.
 *
 * <p><b>Why brackets and a scrim rather than the old dashed box.</b> The previous version copied
 * {@code bg_dropzone.xml} so the capture screen matched the home screen's drop target. That was the
 * right instinct for a static hint and the wrong one here: a full dashed outline cannot be told apart
 * from the label's own printed borders, and a uniform stroke has nowhere to put state. Four brackets
 * read as instrumentation at a glance, and the scrim does the real work of showing where the window
 * is by simply making everything else darker.
 *
 * <p><b>Colours come from the app palette, pinned to their light tones.</b> Every pixel behind this
 * view is camera output, whose brightness has nothing to do with the app's light/dark setting, so the
 * {@code camera_guide_*} colours are the palette's own values with no {@code values-night} override:
 * {@code md_theme_outline_variant} light for neutral, {@code status_warning_container} light for
 * amber — the same amber the analysis screen uses for a precautionary verdict — and
 * {@code md_theme_primary} night for the brand green. The first version resolved
 * {@code colorOutlineVariant} through the theme instead, which is {@code #3D493E} at night: a dark
 * green line, effectively invisible over a preview.
 */
public final class GuideOverlayView extends View {

    /** Default window width, as a fraction of the view, before any lock-on. */
    private static final float WIDTH_FRACTION = 0.85f;

    /** Default window aspect. Ingredient panels are wider than tall far more often than not. */
    private static final float ASPECT_HEIGHT_OVER_WIDTH = 0.72f;

    /** Matches ShapeAppearance.AllergyCheck.Medium, so the window echoes the app's shape scale. */
    private static final float CORNER_RADIUS_DP = 16f;
    private static final float FRAME_STROKE_DP = 1.5f;
    private static final float BRACKET_STROKE_DP = 3f;
    private static final float DASH_WIDTH_DP = 8f;
    private static final float DASH_GAP_DP = 6f;
    private static final float LINE_STROKE_DP = 1f;
    private static final float LINE_CORNER_DP = 2f;

    /** Padding added around recognised text so the brackets sit outside it, not on it. */
    private static final float LOCK_PADDING_DP = 12f;

    /** Bracket arm length: a share of the window's shorter side, clamped so it always reads as an L. */
    private static final float BRACKET_ARM_FRACTION = 0.18f;
    private static final float BRACKET_ARM_MIN_DP = 20f;
    private static final float BRACKET_ARM_MAX_DP = 56f;

    /**
     * Smallest window the lock-on may shrink to, as a fraction of the view. A single recognised line
     * has almost no height, and without a floor the brackets collapse into an unreadable sliver.
     */
    private static final float MIN_LOCK_WIDTH_FRACTION = 0.4f;
    private static final float MIN_LOCK_HEIGHT_FRACTION = 0.18f;

    /**
     * Per-frame interpolation factor towards the target window and colour.
     *
     * <p>A plain lerp driven by {@link #postInvalidateOnAnimation()} rather than a {@code ValueAnimator}:
     * targets arrive four times a second, and restarting an animator on each one is precisely what
     * makes these overlays look jittery. At 0.25 the window closes ~90% of the gap in eight frames,
     * about 130 ms — fast enough to feel like tracking, slow enough to absorb a noisy box.
     */
    private static final float LERP = 0.25f;

    /** Below this the lerp has arrived and the animation loop can stop. */
    private static final float SETTLED_PX = 0.5f;
    private static final int SETTLED_COLOR_STEP = 2;

    /** Lines drawn per frame. A dense nutrition panel can yield far more, and they add nothing. */
    private static final int MAX_DRAWN_LINES = 24;

    /** One sweep of the searching scan line. */
    private static final long SCAN_PERIOD_MILLIS = 1600L;

    /** Scan band height, as a fraction of the window. */
    private static final float SCAN_BAND_FRACTION = 0.18f;

    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Reused every frame: onDraw runs at display rate and must not allocate. */
    private final Path scrimPath = new Path();
    private final Path bracketPath = new Path();
    private final RectF defaultWindow = new RectF();
    private final RectF targetWindow = new RectF();
    private final RectF currentWindow = new RectF();
    private final RectF scratch = new RectF();
    private final List<RectF> detectedLines = new ArrayList<>();

    private final DashPathEffect searchingDash;
    private final float density;
    private final float cornerRadius;

    @ColorInt
    private int neutralColor;
    @ColorInt
    private int adjustColor;
    @ColorInt
    private int readyColor;
    @ColorInt
    private int scanColor;
    @ColorInt
    private int highlightColor;

    @ColorInt
    private int targetColor;
    @ColorInt
    private int currentColor;

    private FrameReadability.Verdict verdict = FrameReadability.Verdict.SEARCHING;
    private boolean hasLock;
    private long scanStartMillis;
    private float scanBandHeight;

    public GuideOverlayView(Context context) {
        this(context, null);
    }

    public GuideOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        cornerRadius = CORNER_RADIUS_DP * density;
        searchingDash = new DashPathEffect(
                new float[] {DASH_WIDTH_DP * density, DASH_GAP_DP * density}, 0f);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        neutralColor = ContextCompat.getColor(context, R.color.camera_guide_neutral);
        adjustColor = ContextCompat.getColor(context, R.color.camera_guide_adjust);
        readyColor = ContextCompat.getColor(context, R.color.camera_guide_ready);
        highlightColor = ContextCompat.getColor(context, R.color.camera_line_highlight);
        scanColor = ContextCompat.getColor(context, R.color.scan_line_center);
        targetColor = neutralColor;
        currentColor = neutralColor;

        scrimPaint.setStyle(Paint.Style.FILL);
        scrimPaint.setColor(ContextCompat.getColor(context, R.color.camera_scrim));

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(FRAME_STROKE_DP * density);
        // Installed here and not left to the first setVerdict call: the initial verdict is already
        // SEARCHING, so setVerdict early-returns on it and the dashed outline that marks "still
        // looking" would never appear until the verdict had changed and come back.
        framePaint.setPathEffect(searchingDash);

        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(BRACKET_STROKE_DP * density);
        bracketPaint.setStrokeCap(Paint.Cap.ROUND);
        bracketPaint.setStrokeJoin(Paint.Join.ROUND);

        lineFillPaint.setStyle(Paint.Style.FILL);
        lineFillPaint.setColor(highlightColor);

        lineStrokePaint.setStyle(Paint.Style.STROKE);
        lineStrokePaint.setStrokeWidth(LINE_STROKE_DP * density);

        scanPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * The fixed window the camera analysis judges against, in view coordinates, or {@code null}
     * before layout.
     *
     * <p>Deliberately the <em>default</em> centred window and never the animated lock-on rect, even
     * though the lock-on is what the user sees. Judging against the lock-on would close a feedback
     * loop: the window would be derived from the text, so the text would always fill it, and the
     * thresholds that ask "is this text big enough" and "does it run off the edge" would be
     * measuring their own output. In practice the frame would stop ever reporting TOO_FAR and start
     * reporting CROPPED permanently. The brackets track the text; the measurement does not.
     */
    @MainThread
    @Nullable
    public RectF judgeWindow() {
        if (defaultWindow.isEmpty()) {
            return null;
        }
        return new RectF(defaultWindow);
    }

    /** Sets the state the frame reports. Cheap to call repeatedly with the same value. */
    @MainThread
    public void setVerdict(@NonNull FrameReadability.Verdict verdict) {
        if (this.verdict == verdict) {
            return;
        }
        this.verdict = verdict;
        framePaint.setPathEffect(
                verdict == FrameReadability.Verdict.SEARCHING ? searchingDash : null);
        targetColor = colorFor(verdict);
        postInvalidateOnAnimation();
    }

    /**
     * Locks the window onto {@code textBounds}, or releases it back to the centred default when
     * {@code null}.
     *
     * <p>The bounds arrive unpadded from {@link FrameReadability}, since how much breathing room to
     * leave is a density-dependent visual choice rather than part of the measurement.
     */
    @MainThread
    public void setLockTarget(@Nullable RectF textBounds) {
        if (textBounds == null || textBounds.isEmpty() || getWidth() == 0) {
            releaseLock();
            return;
        }
        float padding = LOCK_PADDING_DP * density;
        scratch.set(textBounds.left - padding, textBounds.top - padding,
                textBounds.right + padding, textBounds.bottom + padding);
        expandToMinimum(scratch);
        if (!clampToView(scratch)) {
            // The text mapped wholly outside the displayed preview, which a cropping scale type
            // makes routine: the analysis stream is 4:3 and FILL_CENTER shows a slice of it, so text
            // the camera can see may sit well off-screen. There is nothing to lock onto, so fall
            // back to the centred default rather than tracking a rect the user cannot see.
            releaseLock();
            return;
        }
        hasLock = true;
        targetWindow.set(scratch);
        postInvalidateOnAnimation();
    }

    /** Returns the window to the centred default, if it is not already there. */
    private void releaseLock() {
        if (hasLock) {
            hasLock = false;
            targetWindow.set(defaultWindow);
            postInvalidateOnAnimation();
        }
    }

    /** Sets the recognised lines to highlight, in view coordinates. The list is copied. */
    @MainThread
    public void setDetectedLines(@Nullable List<RectF> lines) {
        detectedLines.clear();
        if (lines != null) {
            int count = Math.min(lines.size(), MAX_DRAWN_LINES);
            for (int index = 0; index < count; index++) {
                detectedLines.add(new RectF(lines.get(index)));
            }
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float windowWidth = w * WIDTH_FRACTION;
        float windowHeight = windowWidth * ASPECT_HEIGHT_OVER_WIDTH;
        float left = (w - windowWidth) / 2f;
        float top = (h - windowHeight) / 2f;
        defaultWindow.set(left, top, left + windowWidth, top + windowHeight);
        if (!hasLock) {
            targetWindow.set(defaultWindow);
        }
        if (currentWindow.isEmpty()) {
            currentWindow.set(targetWindow);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (currentWindow.isEmpty()) {
            // Re-seeded rather than returned on, so an empty window can never become permanent: a
            // bare return here would skip advance(), and the lerp that is the only thing able to
            // move the window back out of empty would never run again.
            currentWindow.set(targetWindow);
            if (currentWindow.isEmpty()) {
                // Genuinely nothing to draw yet — onSizeChanged has not run.
                return;
            }
        }
        boolean animating = advance();

        drawScrim(canvas);
        if (verdict != FrameReadability.Verdict.SEARCHING) {
            drawDetectedLines(canvas);
        }
        canvas.drawRoundRect(currentWindow, cornerRadius, cornerRadius, framePaint);
        drawBrackets(canvas);
        if (verdict == FrameReadability.Verdict.SEARCHING) {
            drawScanLine(canvas);
            animating = true;
        }

        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    /** Steps the window and colour towards their targets. Returns whether another frame is needed. */
    private boolean advance() {
        float left = lerp(currentWindow.left, targetWindow.left);
        float top = lerp(currentWindow.top, targetWindow.top);
        float right = lerp(currentWindow.right, targetWindow.right);
        float bottom = lerp(currentWindow.bottom, targetWindow.bottom);
        boolean moving = Math.abs(left - targetWindow.left) > SETTLED_PX
                || Math.abs(top - targetWindow.top) > SETTLED_PX
                || Math.abs(right - targetWindow.right) > SETTLED_PX
                || Math.abs(bottom - targetWindow.bottom) > SETTLED_PX;
        currentWindow.set(left, top, right, bottom);

        currentColor = lerpColor(currentColor, targetColor);
        boolean recolouring = !colorSettled(currentColor, targetColor);
        framePaint.setColor(withAlphaFraction(currentColor, 0.55f));
        bracketPaint.setColor(currentColor);
        lineStrokePaint.setColor(withAlphaFraction(currentColor, 0.4f));

        return moving || recolouring;
    }

    private float lerp(float current, float target) {
        return current + (target - current) * LERP;
    }

    private void drawScrim(@NonNull Canvas canvas) {
        // EVEN_ODD punches the window out of a full-bleed rectangle in one fill. Cheaper and far
        // more predictable on a hardware canvas than saveLayer plus a PorterDuff.CLEAR pass.
        scrimPath.rewind();
        scrimPath.setFillType(Path.FillType.EVEN_ODD);
        scrimPath.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
        scrimPath.addRoundRect(currentWindow, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.drawPath(scrimPath, scrimPaint);
    }

    private void drawDetectedLines(@NonNull Canvas canvas) {
        if (detectedLines.isEmpty()) {
            return;
        }
        float radius = LINE_CORNER_DP * density;
        int save = canvas.save();
        // Clipped to the window so text the user has not framed is not advertised as captured.
        canvas.clipRect(currentWindow);
        for (int index = 0; index < detectedLines.size(); index++) {
            RectF line = detectedLines.get(index);
            canvas.drawRoundRect(line, radius, radius, lineFillPaint);
            canvas.drawRoundRect(line, radius, radius, lineStrokePaint);
        }
        canvas.restoreToCount(save);
    }

    /**
     * Four rounded corner brackets tracing the window.
     *
     * <p>Each arm runs along the edge to where the corner curve begins, follows that curve with an
     * arc of the same radius the scrim was punched with, and runs back out along the other edge. The
     * arc matters: a straight line into the corner cuts across the rounded cut-out and reads as a
     * stray diagonal rather than as a bracket.
     */
    private void drawBrackets(@NonNull Canvas canvas) {
        float arm = Math.min(currentWindow.width(), currentWindow.height()) * BRACKET_ARM_FRACTION;
        arm = Math.max(BRACKET_ARM_MIN_DP * density,
                Math.min(BRACKET_ARM_MAX_DP * density, arm));
        float radius = Math.min(cornerRadius,
                Math.min(currentWindow.width(), currentWindow.height()) / 2f);
        float diameter = radius * 2f;

        bracketPath.rewind();

        // Top-left: up the left edge, round the corner, out along the top.
        bracketPath.moveTo(currentWindow.left, currentWindow.top + radius + arm);
        bracketPath.lineTo(currentWindow.left, currentWindow.top + radius);
        scratch.set(currentWindow.left, currentWindow.top,
                currentWindow.left + diameter, currentWindow.top + diameter);
        bracketPath.arcTo(scratch, 180f, 90f, false);
        bracketPath.lineTo(currentWindow.left + radius + arm, currentWindow.top);

        // Top-right.
        bracketPath.moveTo(currentWindow.right - radius - arm, currentWindow.top);
        bracketPath.lineTo(currentWindow.right - radius, currentWindow.top);
        scratch.set(currentWindow.right - diameter, currentWindow.top,
                currentWindow.right, currentWindow.top + diameter);
        bracketPath.arcTo(scratch, 270f, 90f, false);
        bracketPath.lineTo(currentWindow.right, currentWindow.top + radius + arm);

        // Bottom-right.
        bracketPath.moveTo(currentWindow.right, currentWindow.bottom - radius - arm);
        bracketPath.lineTo(currentWindow.right, currentWindow.bottom - radius);
        scratch.set(currentWindow.right - diameter, currentWindow.bottom - diameter,
                currentWindow.right, currentWindow.bottom);
        bracketPath.arcTo(scratch, 0f, 90f, false);
        bracketPath.lineTo(currentWindow.right - radius - arm, currentWindow.bottom);

        // Bottom-left.
        bracketPath.moveTo(currentWindow.left + radius + arm, currentWindow.bottom);
        bracketPath.lineTo(currentWindow.left + radius, currentWindow.bottom);
        scratch.set(currentWindow.left, currentWindow.bottom - diameter,
                currentWindow.left + diameter, currentWindow.bottom);
        bracketPath.arcTo(scratch, 90f, 90f, false);
        bracketPath.lineTo(currentWindow.left, currentWindow.bottom - radius - arm);

        canvas.drawPath(bracketPath, bracketPaint);
    }

    /**
     * The sweeping band shown while searching.
     *
     * <p>The gradient is rebuilt whenever the band height changes rather than built once in
     * {@link #onSizeChanged}: the shader is anchored in local coordinates, so a shader sized to the
     * view and a rect sized to the window do not line up, and the band ends in a hard horizontal cut
     * instead of fading out.
     */
    private void drawScanLine(@NonNull Canvas canvas) {
        float bandHeight = currentWindow.height() * SCAN_BAND_FRACTION;
        if (bandHeight <= 0f) {
            return;
        }
        if (scanPaint.getShader() == null || Math.abs(bandHeight - scanBandHeight) > 0.5f) {
            scanBandHeight = bandHeight;
            scanPaint.setShader(new LinearGradient(0f, 0f, 0f, bandHeight,
                    new int[] {Color.TRANSPARENT, scanColor, Color.TRANSPARENT},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (scanStartMillis == 0L) {
            scanStartMillis = now;
        }
        float phase = ((now - scanStartMillis) % SCAN_PERIOD_MILLIS) / (float) SCAN_PERIOD_MILLIS;
        // Triangle wave, so the band sweeps down and back rather than snapping to the top.
        float sweep = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
        float top = currentWindow.top + (currentWindow.height() - bandHeight) * sweep;

        int save = canvas.save();
        canvas.clipRect(currentWindow);
        canvas.translate(currentWindow.left, top);
        canvas.drawRect(0f, 0f, currentWindow.width(), bandHeight, scanPaint);
        canvas.restoreToCount(save);
    }

    @ColorInt
    private int colorFor(@NonNull FrameReadability.Verdict verdict) {
        switch (verdict) {
            case READY:
                return readyColor;
            case SEARCHING:
                return neutralColor;
            default:
                // Every remaining verdict is "something needs changing", and they all read amber so
                // the frame carries one bit of meaning the user can act on without reading the hint.
                return adjustColor;
        }
    }

    private void expandToMinimum(@NonNull RectF rect) {
        float minWidth = getWidth() * MIN_LOCK_WIDTH_FRACTION;
        float minHeight = getHeight() * MIN_LOCK_HEIGHT_FRACTION;
        if (rect.width() < minWidth) {
            float grow = (minWidth - rect.width()) / 2f;
            rect.left -= grow;
            rect.right += grow;
        }
        if (rect.height() < minHeight) {
            float grow = (minHeight - rect.height()) / 2f;
            rect.top -= grow;
            rect.bottom += grow;
        }
    }

    /**
     * Clamps {@code rect} to the view, returning whether anything of it survived.
     *
     * <p>The return value is the point. Clamping each edge against its own bound is only valid for a
     * rect that overlaps the view at all; for one lying entirely off it the two bounds cross and the
     * result is <em>inverted</em> — {@code left > right} or {@code top > bottom}. An inverted rect is
     * {@link RectF#isEmpty()}, and an empty {@code currentWindow} used to make {@link #onDraw} return
     * before it could lerp back out, latching the whole overlay blank for the rest of the screen's
     * life. Rejecting it here is the first of the two guards; re-seeding in {@code onDraw} is the
     * second.
     */
    private boolean clampToView(@NonNull RectF rect) {
        float margin = LOCK_PADDING_DP * density;
        rect.left = Math.max(margin, rect.left);
        rect.top = Math.max(margin, rect.top);
        rect.right = Math.min(getWidth() - margin, rect.right);
        rect.bottom = Math.min(getHeight() - margin, rect.bottom);
        return rect.left < rect.right && rect.top < rect.bottom;
    }

    @ColorInt
    private static int lerpColor(@ColorInt int current, @ColorInt int target) {
        return Color.argb(
                lerpChannel(Color.alpha(current), Color.alpha(target)),
                lerpChannel(Color.red(current), Color.red(target)),
                lerpChannel(Color.green(current), Color.green(target)),
                lerpChannel(Color.blue(current), Color.blue(target)));
    }

    private static int lerpChannel(int current, int target) {
        int next = current + Math.round((target - current) * LERP);
        if (next == current && current != target) {
            // Rounding stalls one level short on small gaps, which would spin the animation loop
            // forever; step the last level by hand.
            next += target > current ? 1 : -1;
        }
        return next;
    }

    private static boolean colorSettled(@ColorInt int current, @ColorInt int target) {
        return Math.abs(Color.alpha(current) - Color.alpha(target)) <= SETTLED_COLOR_STEP
                && Math.abs(Color.red(current) - Color.red(target)) <= SETTLED_COLOR_STEP
                && Math.abs(Color.green(current) - Color.green(target)) <= SETTLED_COLOR_STEP
                && Math.abs(Color.blue(current) - Color.blue(target)) <= SETTLED_COLOR_STEP;
    }

    @ColorInt
    private static int withAlphaFraction(@ColorInt int color, float fraction) {
        return Color.argb(Math.round(Color.alpha(color) * fraction),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
