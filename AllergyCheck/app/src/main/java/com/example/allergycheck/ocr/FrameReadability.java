package com.example.allergycheck.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides, for one live camera frame, whether the app can read the ingredient list — and if not,
 * which single thing the user should change.
 *
 * <p>This is the judgement a bank's cheque scanner makes: it does not simply refuse a bad frame, it
 * names the fault ("move closer") so the next frame is better. Everything here is about that one
 * output — {@link Verdict} — and the rectangle the guide should lock onto.
 *
 * <p><b>Exactly one instruction is reported, never a list.</b> Showing two corrections at once is
 * how a user ends up fixing neither. {@link #evaluate} walks a fixed precedence order and returns
 * the first fault it finds.
 *
 * <p><b>Deliberately few checks.</b> Earlier versions also judged focus, centring, tilt, and whether
 * the text ran off the guide window. Every one of them was measured against a real label on a real
 * device and removed:
 * <ul>
 *   <li><b>Focus</b> — no cheap metric separated a softly-focused panel from a crisply printed faint
 *       one reliably enough to act on.</li>
 *   <li><b>Centring</b> — it does not matter. The recogniser reads text wherever it sits in frame,
 *       and the guide brackets track the text rather than the other way round.</li>
 *   <li><b>Tilt</b> — {@code ML Kit} reported a median line angle of about -90 degrees on text that
 *       was plainly horizontal on screen, so the signal does not mean what the check assumed and
 *       "straighten the camera" fired on perfectly straight labels.</li>
 *   <li><b>Cut off</b> — counting lines near the guide window's edge flagged every label that
 *       properly filled the window, which is the thing the user was being asked to do.</li>
 * </ul>
 * Each of those blocked {@link Verdict#READY} on frames the recogniser was reading perfectly, which
 * in turn meant auto-capture could never fire. What is left is the question that actually matters:
 * is there enough text, and does it parse as an ingredient list?
 *
 * <p><b>No Android imports, deliberately.</b> {@code android.graphics.Rect} is a throwing stub on
 * the JVM unit-test classpath, so geometry arrives as the plain-float {@link Box} below. For the
 * same reason the class takes a pre-computed {@link TextSignal} rather than raw text: deciding
 * whether an ingredient list is present belongs to
 * {@link com.example.allergycheck.ingredients.IngredientExtractor}, and reaching for it from here
 * would make the OCR layer depend on the layer above it.
 */
public final class FrameReadability {

    /** What the frame needs, in the order {@link #evaluate} checks for it. */
    public enum Verdict {
        /** Nothing text-like in the frame at all. */
        SEARCHING,
        /** Text is there but too small to recognise reliably. */
        TOO_FAR,
        /** Readable text, but it is not an ingredient list — typically the nutrition panel. */
        NO_INGREDIENT_LIST,
        /** The "Ingredients" heading is visible but too little of the list with it. */
        HEADING_ONLY,
        /** Good enough to shoot. */
        READY;

        public boolean isReady() {
            return this == READY;
        }
    }

    /** How far the recognised text got towards being a usable ingredient list. */
    public enum TextSignal {
        /** No text recognised. */
        NONE,
        /** Text recognised, but no ingredient heading and nothing parsable. */
        TEXT_ONLY,
        /** An "Ingredients"-style heading matched, but no list could be parsed under it. */
        HEADING_VISIBLE,
        /** A heading plus ingredient items parsed cleanly — the same test the real analysis applies. */
        LIST_PARSED
    }

    /**
     * Minimum median line height, as a fraction of the guide window's height, for text to be worth
     * recognising. A fraction rather than a pixel count so the number holds across preview
     * resolutions. Measured on a real label at a comfortable reading distance this sits between
     * 0.03 and 0.10, and the recogniser starts dropping characters below roughly 0.025.
     */
    static final float MIN_MEDIAN_LINE_HEIGHT_FRACTION = 0.025f;

    /** Fewer lines than this is a glimpse of a label, not a list to read. */
    static final int MIN_LINES = 3;

    /**
     * Lines needed before a parsed list is trusted enough to shoot.
     *
     * <p>Four, not the six an earlier version used: a small package's ingredient panel is commonly
     * three or four lines, and six was high enough to hold frames at
     * {@link Verdict#HEADING_ONLY} that the post-capture analysis went on to read perfectly.
     */
    static final int MIN_LINES_FOR_READY = 4;

    private FrameReadability() {
    }

    /** An axis-aligned box around one recognised line. Plain floats; see the class note. */
    public static final class Box {

        public final float left;
        public final float top;
        public final float right;
        public final float bottom;

        public Box(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public float width() {
            return right - left;
        }

        public float height() {
            return bottom - top;
        }

        public float centerX() {
            return (left + right) / 2f;
        }

        public float centerY() {
            return (top + bottom) / 2f;
        }

        /** Zero for an inverted or degenerate box. */
        public float area() {
            return Math.max(0f, width()) * Math.max(0f, height());
        }

        @Override
        public String toString() {
            return "Box[" + left + "," + top + " " + right + "," + bottom + "]";
        }
    }

    /** A verdict together with the text the guide should lock onto. */
    public static final class Assessment {

        private final Verdict verdict;
        private final Box textBounds;
        private final List<Box> lines;

        Assessment(Verdict verdict, Box textBounds, List<Box> lines) {
            this.verdict = verdict;
            this.textBounds = textBounds;
            this.lines = lines;
        }

        public Verdict verdict() {
            return verdict;
        }

        /**
         * Bounding box of the text the verdict is about, or {@code null} when no text was found.
         *
         * <p>Raw, with no padding: the view adds that, since breathing room is a density-dependent
         * visual choice rather than part of the measurement.
         */
        public Box textBounds() {
            return textBounds;
        }

        /** The lines the verdict was computed over; unmodifiable, possibly empty. */
        public List<Box> lines() {
            return lines;
        }
    }

    /**
     * Judges one frame.
     *
     * <p>Note that the checks run over <em>every</em> recognised line, not only those inside
     * {@code window}. The window is a visual aid and a scale reference; where in the frame the text
     * happens to sit has no bearing on whether it can be read.
     *
     * @param lines  recognised line boxes, already mapped into the same coordinate space as
     *               {@code window} (that is, view coordinates)
     * @param window the guide window, used only to scale the line-height threshold
     * @param signal how far the recognised text got towards being an ingredient list
     */
    public static Assessment evaluate(List<Box> lines, Box window, TextSignal signal) {
        if (lines == null || lines.isEmpty() || window == null || window.area() <= 0f) {
            return new Assessment(Verdict.SEARCHING, null, Collections.<Box>emptyList());
        }

        List<Box> all = Collections.unmodifiableList(new ArrayList<>(lines));
        Box bounds = union(all);

        if (medianHeight(all) < window.height() * MIN_MEDIAN_LINE_HEIGHT_FRACTION
                || all.size() < MIN_LINES) {
            // Both mean "there is not enough label to work with", and the same action fixes either.
            return new Assessment(Verdict.TOO_FAR, bounds, all);
        }

        switch (signal) {
            case LIST_PARSED:
                if (all.size() < MIN_LINES_FOR_READY) {
                    // A heading and items off two or three lines is probably a truncated list.
                    // Going green here is the one error that actively misleads: the user would
                    // shoot believing they had captured the whole thing.
                    return new Assessment(Verdict.HEADING_ONLY, bounds, all);
                }
                return new Assessment(Verdict.READY, bounds, all);
            case HEADING_VISIBLE:
                return new Assessment(Verdict.HEADING_ONLY, bounds, all);
            case TEXT_ONLY:
            case NONE:
            default:
                // Readable text that is not an ingredient list. In practice this is the nutrition
                // panel next door, which is why the message names the heading to look for.
                return new Assessment(Verdict.NO_INGREDIENT_LIST, bounds, all);
        }
    }

    private static Box union(List<Box> boxes) {
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (int index = 0; index < boxes.size(); index++) {
            Box box = boxes.get(index);
            left = Math.min(left, box.left);
            top = Math.min(top, box.top);
            right = Math.max(right, box.right);
            bottom = Math.max(bottom, box.bottom);
        }
        return new Box(left, top, right, bottom);
    }

    /**
     * Median rather than mean line height. A label almost always carries one or two lines of large
     * type — a brand name, a "NUTRITION FACTS" heading — and a mean is dragged far enough up by them
     * to call a frame close enough while every ingredient line is still too small to read.
     */
    private static float medianHeight(List<Box> boxes) {
        float[] heights = new float[boxes.size()];
        for (int index = 0; index < boxes.size(); index++) {
            heights[index] = boxes.get(index).height();
        }
        java.util.Arrays.sort(heights);
        int middle = heights.length / 2;
        if (heights.length % 2 == 1) {
            return heights[middle];
        }
        return (heights[middle - 1] + heights[middle]) / 2f;
    }
}
