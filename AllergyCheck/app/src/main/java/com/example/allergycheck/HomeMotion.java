package com.example.allergycheck;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** The home screen's animations: the photo flying between its two places, fades and reveals. */
final class HomeMotion {
    /** Material "emphasized decelerate": quick start, long soft landing. */
    static final PathInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final PathInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final long FLIGHT_MS = 480;

    private HomeMotion() {
    }

    /**
     * Fades views that disappear out quickly and views that appear in once the photo has mostly
     * landed. The photo views themselves are excluded: they move with {@link #fly}.
     */
    @NonNull
    static Transition pageTransition(@NonNull View... excluded) {
        Fade fadeOut = new Fade(Fade.OUT);
        fadeOut.setDuration(140);
        Fade fadeIn = new Fade(Fade.IN);
        fadeIn.setStartDelay(220);
        fadeIn.setDuration(260);
        fadeIn.setInterpolator(STANDARD);
        TransitionSet set = new TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(fadeOut)
                .addTransition(fadeIn);
        for (View view : excluded) {
            set.excludeTarget(view, true);
        }
        return set;
    }

    /** Where {@code view} currently is, in {@code root}'s coordinates. */
    @NonNull
    static RectF boundsIn(@NonNull View root, @NonNull View view) {
        int[] rootLocation = new int[2];
        int[] viewLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        view.getLocationInWindow(viewLocation);
        float left = viewLocation[0] - rootLocation[0];
        float top = viewLocation[1] - rootLocation[1];
        return new RectF(left, top, left + view.getWidth(), top + view.getHeight());
    }

    /** The part of a fitCenter image view the bitmap is actually drawn in, in root coordinates. */
    @NonNull
    static RectF fitCenterBoundsIn(@NonNull View root, @NonNull View imageView,
                                   @NonNull Bitmap bitmap) {
        RectF bounds = boundsIn(root, imageView);
        float scale = Math.min(bounds.width() / bitmap.getWidth(),
                bounds.height() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = bounds.centerX() - width / 2f;
        float top = bounds.centerY() - height / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    /**
     * Moves a copy of {@code bitmap} from {@code from} to wherever {@code target} lands after the
     * next layout. The copy is drawn in {@code root}'s overlay, clipped to {@code clip} (the
     * scrolling area) so it never covers the top bar, and {@code hidden} stays invisible until the
     * copy arrives. The copy is cropped like centerCrop so it is never stretched.
     *
     * @param targetBounds computes the landing rectangle once {@code target} is laid out.
     * @return the flight, so a newer one can end it; null when nothing is animated.
     */
    @Nullable
    static Flight fly(@NonNull ViewGroup root, @NonNull Bitmap bitmap, @NonNull RectF from,
                      @NonNull RectF clip, float fromRadius, float toRadius,
                      @NonNull View target, @NonNull View hidden,
                      @NonNull BoundsProvider targetBounds) {
        if (!ValueAnimator.areAnimatorsEnabled() || from.isEmpty()) {
            return null;
        }
        Flight flight = new Flight(root, hidden);
        hidden.setAlpha(0f);
        // Measure the landing spot after the new layout but before it is drawn.
        flight.preDraw = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                flight.preDraw = null;
                RectF to = targetBounds.bounds();
                if (to == null || to.isEmpty() || !target.isAttachedToWindow()) {
                    flight.finish();
                    return true;
                }
                flight.start(new CropDrawable(bitmap, clip), from, to, fromRadius, toRadius);
                return true;
            }
        };
        root.getViewTreeObserver().addOnPreDrawListener(flight.preDraw);
        return flight;
    }

    /** One photo flight; {@link #end()} jumps it to the end and cleans up. */
    static final class Flight {
        private final ViewGroup root;
        private final View hidden;
        private ViewTreeObserver.OnPreDrawListener preDraw;
        private ValueAnimator animator;
        private CropDrawable copy;
        private boolean finished;

        private Flight(ViewGroup root, View hidden) {
            this.root = root;
            this.hidden = hidden;
        }

        private void start(CropDrawable drawable, RectF from, RectF to,
                           float fromRadius, float toRadius) {
            copy = drawable;
            RectF frame = new RectF();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(FLIGHT_MS);
            animator.setInterpolator(EMPHASIZED);
            animator.addUpdateListener(animation -> {
                float t = (float) animation.getAnimatedValue();
                frame.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                        lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
                copy.setFrame(frame, lerp(fromRadius, toRadius, t));
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finish();
                }
            });
            copy.setFrame(from, fromRadius);
            root.getOverlay().add(copy);
            animator.start();
        }

        /** Ends the flight now: the copy is removed and the real photo shown. */
        void end() {
            if (preDraw != null) {
                root.getViewTreeObserver().removeOnPreDrawListener(preDraw);
                preDraw = null;
            }
            if (animator != null) {
                animator.end();
            }
            finish();
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (copy != null) {
                root.getOverlay().remove(copy);
            }
            hidden.setAlpha(1f);
        }
    }

    /** Draws a bitmap centre-cropped into a rounded frame, clipped to a fixed area. */
    private static final class CropDrawable extends Drawable {
        private final Bitmap bitmap;
        private final RectF clip;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF frame = new RectF();
        private float radius;

        CropDrawable(Bitmap bitmap, RectF clip) {
            this.bitmap = bitmap;
            this.clip = new RectF(clip);
            paint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP));
        }

        void setFrame(RectF newFrame, float newRadius) {
            frame.set(newFrame);
            radius = newRadius;
            float scale = Math.max(frame.width() / bitmap.getWidth(),
                    frame.height() / bitmap.getHeight());
            matrix.setScale(scale, scale);
            matrix.postTranslate(frame.centerX() - bitmap.getWidth() * scale / 2f,
                    frame.centerY() - bitmap.getHeight() * scale / 2f);
            paint.getShader().setLocalMatrix(matrix);
            // The overlay only redraws the area inside the drawable's bounds.
            setBounds(Math.round(clip.left), Math.round(clip.top),
                    Math.round(clip.right), Math.round(clip.bottom));
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int save = canvas.save();
            canvas.clipRect(clip);
            canvas.drawRoundRect(frame, radius, radius, paint);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Slides and fades each visible child of {@code parent} in, one after another. */
    static void revealChildren(@NonNull ViewGroup parent, long startDelay) {
        float offset = 16 * parent.getResources().getDisplayMetrics().density;
        long delay = startDelay;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            riseIn(child, offset, delay);
            delay += 60;
        }
    }

    /** Slides one view up into place while fading it in. */
    static void riseIn(@NonNull View view, float offset, long delay) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(offset);
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320)
                .setInterpolator(EMPHASIZED).start();
    }

    /** A small pop for the verdict badge. */
    static void popIn(@NonNull View view, long delay) {
        view.animate().cancel();
        view.setScaleX(0.4f);
        view.setScaleY(0.4f);
        view.animate().scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(360)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    /**
     * Repeatedly sweeps {@code line} down over {@code container} to show the photo is being read.
     * Returns the running animator, or null when animations are off.
     */
    @Nullable
    static ValueAnimator scan(@NonNull View line, @NonNull View container) {
        // A fade-out from the previous scan may still be running on the line.
        line.animate().cancel();
        line.setVisibility(View.VISIBLE);
        line.setAlpha(1f);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            return null;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(STANDARD);
        animator.addUpdateListener(animation -> {
            float t = (float) animation.getAnimatedValue();
            int height = line.getHeight();
            line.setTranslationY(lerp(-height, container.getHeight(), t));
        });
        animator.start();
        return animator;
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    interface BoundsProvider {
        @Nullable
        RectF bounds();
    }
}
