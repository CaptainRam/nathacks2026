package com.example.allergycheck.ocr;

import static org.junit.Assert.assertEquals;

import com.example.allergycheck.ocr.FrameReadability.Verdict;

import org.junit.Before;
import org.junit.Test;

/**
 * Time is passed in, so these run instantly and pin the exact frame counts the capture screen's feel
 * depends on. {@code STEP} is one analysis interval at the live path's ~4 fps.
 */
public class VerdictStabilizerTest {

    private static final long STEP = 250L;

    private VerdictStabilizer stabilizer;
    private long now;

    @Before
    public void setUp() {
        stabilizer = new VerdictStabilizer();
        now = 10_000L;
    }

    @Test
    public void startsSearching() {
        assertEquals(Verdict.SEARCHING, stabilizer.displayed());
    }

    @Test
    public void oneStrayFrameChangesNothing() {
        // The point of the whole class: a single marginal frame must not move the display.
        assertEquals(Verdict.SEARCHING, feed(Verdict.READY));
    }

    @Test
    public void twoAgreeingFramesCommit() {
        feed(Verdict.TOO_FAR);
        assertEquals(Verdict.TOO_FAR, feed(Verdict.TOO_FAR));
    }

    @Test
    public void flappingCommitsNothing() {
        // Alternating verdicts never accumulate agreement, so the screen stays still rather than
        // strobing between two messages.
        feed(Verdict.TOO_FAR);
        feed(Verdict.NO_INGREDIENT_LIST);
        feed(Verdict.TOO_FAR);
        assertEquals(Verdict.SEARCHING, feed(Verdict.NO_INGREDIENT_LIST));
    }

    @Test
    public void leavingReadyTakesMoreAgreementThanReachingIt() {
        // Asymmetric on purpose: reaching for the shutter shakes the phone, so the frames taken
        // while a thumb is travelling are the worst of the session and must not disable the button
        // under it.
        commitReady();
        waitOutDwell();
        assertEquals(Verdict.READY, feed(Verdict.TOO_FAR));
        assertEquals(Verdict.READY, feed(Verdict.TOO_FAR));
        assertEquals(Verdict.TOO_FAR, feed(Verdict.TOO_FAR));
    }

    @Test
    public void dwellHoldsAMessageLongEnoughToRead() {
        commitReady();
        // Enough frames to agree several times over, but all inside the dwell, so READY stays put.
        for (int frame = 0; frame < 4; frame++) {
            assertEquals(Verdict.READY, feedAfter(Verdict.TOO_FAR, 60L));
        }
    }

    @Test
    public void commitLandsOnTheFirstFramePastTheDwell() {
        // Agreement already banked during the dwell is kept rather than restarted, so the change
        // happens the moment it is allowed to.
        commitReady();
        feedAfter(Verdict.TOO_FAR, 60L);
        feedAfter(Verdict.TOO_FAR, 60L);
        feedAfter(Verdict.TOO_FAR, 60L);
        assertEquals(Verdict.TOO_FAR,
                feedAfter(Verdict.TOO_FAR, VerdictStabilizer.MIN_DWELL_MILLIS));
    }

    @Test
    public void returningToTheDisplayedVerdictClearsAPartialChange() {
        commitReady();
        waitOutDwell();
        feed(Verdict.TOO_FAR);
        feed(Verdict.TOO_FAR);
        // Back to READY: the two TOO_FAR frames must be forgotten, not left one short of a commit.
        feed(Verdict.READY);
        assertEquals(Verdict.READY, feed(Verdict.TOO_FAR));
    }

    @Test
    public void refreshFallsBackToSearchingWhenFramesStop() {
        // A stale "ready to shoot" is the single most misleading thing this screen could show.
        commitReady();
        now += VerdictStabilizer.STALE_MILLIS + 1L;
        assertEquals(Verdict.SEARCHING, stabilizer.refresh(now));
    }

    @Test
    public void refreshLeavesAFreshVerdictAlone() {
        commitReady();
        now += 100L;
        assertEquals(Verdict.READY, stabilizer.refresh(now));
    }

    @Test
    public void refreshBeforeAnyFrameIsHarmless() {
        assertEquals(Verdict.SEARCHING, stabilizer.refresh(now + 100_000L));
    }

    private Verdict feed(Verdict verdict) {
        return feedAfter(verdict, STEP);
    }

    /** One frame arriving {@code deltaMillis} after the last. */
    private Verdict feedAfter(Verdict verdict, long deltaMillis) {
        now += deltaMillis;
        return stabilizer.accept(verdict, now);
    }

    private void commitReady() {
        feed(Verdict.READY);
        assertEquals(Verdict.READY, feed(Verdict.READY));
    }

    private void waitOutDwell() {
        now += VerdictStabilizer.MIN_DWELL_MILLIS;
    }
}
