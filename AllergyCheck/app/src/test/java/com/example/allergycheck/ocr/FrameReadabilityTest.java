package com.example.allergycheck.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ocr.FrameReadability.Box;
import com.example.allergycheck.ocr.FrameReadability.TextSignal;
import com.example.allergycheck.ocr.FrameReadability.Verdict;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one threshold that scales is a fraction of the guide window, so every case here builds a
 * notional 1000x1000 window and places lines against it.
 */
public class FrameReadabilityTest {

    private static final Box WINDOW = new Box(0f, 0f, 1000f, 1000f);

    @Test
    public void noLinesIsSearching() {
        assertEquals(Verdict.SEARCHING, FrameReadability.evaluate(
                Collections.<Box>emptyList(), WINDOW, TextSignal.NONE).verdict());
        assertEquals(Verdict.SEARCHING,
                FrameReadability.evaluate(null, WINDOW, TextSignal.NONE).verdict());
    }

    @Test
    public void searchingCarriesNoLockTarget() {
        // The overlay releases back to its default window on a null bound, so this must stay null
        // rather than becoming an empty box.
        assertNull(FrameReadability.evaluate(Collections.<Box>emptyList(), WINDOW, TextSignal.NONE)
                .textBounds());
    }

    @Test
    public void smallDistantTextIsTooFar() {
        // Lines 10px tall in a 1000px window: 1%, under the 2.5% the recogniser needs.
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            lines.add(new Box(300f, 400f + index * 14f, 700f, 410f + index * 14f));
        }
        assertEquals(Verdict.TOO_FAR,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void tooFewLinesIsTooFar() {
        List<Box> lines = new ArrayList<>();
        lines.add(new Box(300f, 480f, 700f, 520f));
        lines.add(new Box(300f, 530f, 700f, 570f));
        assertEquals(Verdict.TOO_FAR,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void readableTextThatIsNotAnIngredientListSaysSo() {
        assertEquals(Verdict.NO_INGREDIENT_LIST, FrameReadability.evaluate(
                goodLines(), WINDOW, TextSignal.TEXT_ONLY).verdict());
    }

    @Test
    public void headingWithoutAParsableListIsPartial() {
        assertEquals(Verdict.HEADING_ONLY, FrameReadability.evaluate(
                goodLines(), WINDOW, TextSignal.HEADING_VISIBLE).verdict());
    }

    @Test
    public void parsedListOnTooFewLinesIsPartialNotReady() {
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            lines.add(new Box(300f, 300f + index * 60f, 700f, 345f + index * 60f));
        }
        assertEquals(Verdict.HEADING_ONLY,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void parsedListIsReady() {
        FrameReadability.Assessment assessment =
                FrameReadability.evaluate(goodLines(), WINDOW, TextSignal.LIST_PARSED);
        assertEquals(Verdict.READY, assessment.verdict());
        assertTrue(assessment.verdict().isReady());
    }

    @Test
    public void aFourLineListIsEnoughToShoot() {
        // Small packages really do carry four-line ingredient panels, and six used to hold them at
        // HEADING_ONLY while the post-capture analysis read them perfectly.
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            lines.add(new Box(300f, 300f + index * 60f, 700f, 345f + index * 60f));
        }
        assertEquals(Verdict.READY,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void offCentreTextIsStillReady() {
        // Where the text sits does not affect whether it can be read, and the guide brackets track
        // the text rather than the other way round.
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            lines.add(new Box(20f, 30f + index * 50f, 420f, 75f + index * 50f));
        }
        assertEquals(Verdict.READY,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void textFillingTheWindowEdgeToEdgeIsStillReady() {
        // Filling the guide is exactly what the user was asked to do; an earlier version counted
        // lines near the window edge and called every well-framed label cut off.
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            lines.add(new Box(1f, 300f + index * 50f, 999f, 345f + index * 50f));
        }
        assertEquals(Verdict.READY,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void textOutsideTheWindowStillCounts() {
        // The window only scales the height threshold. Lines beyond it are real text and are
        // measured like any other.
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            lines.add(new Box(1200f, 300f + index * 50f, 1600f, 345f + index * 50f));
        }
        assertEquals(Verdict.READY,
                FrameReadability.evaluate(lines, WINDOW, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void lockTargetIsTheUnionOfTheLines() {
        Box bounds = FrameReadability.evaluate(goodLines(), WINDOW, TextSignal.LIST_PARSED)
                .textBounds();
        assertEquals(300f, bounds.left, 0.01f);
        assertEquals(700f, bounds.right, 0.01f);
        assertEquals(300f, bounds.top, 0.01f);
        assertEquals(695f, bounds.bottom, 0.01f);
    }

    @Test
    public void verdictsAreResolutionIndependent() {
        // The height threshold is a fraction for exactly this reason: the same framing at a
        // different preview size must produce the same advice.
        Box bigWindow = new Box(0f, 0f, 3000f, 3000f);
        List<Box> scaled = new ArrayList<>();
        for (Box line : goodLines()) {
            scaled.add(new Box(line.left * 3f, line.top * 3f, line.right * 3f, line.bottom * 3f));
        }
        assertEquals(
                FrameReadability.evaluate(goodLines(), WINDOW, TextSignal.LIST_PARSED).verdict(),
                FrameReadability.evaluate(scaled, bigWindow, TextSignal.LIST_PARSED).verdict());
    }

    @Test
    public void degenerateWindowIsSearchingRatherThanDividingByZero() {
        assertEquals(Verdict.SEARCHING, FrameReadability.evaluate(
                goodLines(), new Box(0f, 0f, 0f, 0f), TextSignal.LIST_PARSED).verdict());
    }

    /** Eight lines, 45px tall, clear of every edge: the frame we want to call READY. */
    private static List<Box> goodLines() {
        List<Box> lines = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            lines.add(new Box(300f, 300f + index * 50f, 700f, 345f + index * 50f));
        }
        return lines;
    }
}
