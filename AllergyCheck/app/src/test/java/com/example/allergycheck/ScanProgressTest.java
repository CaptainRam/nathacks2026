package com.example.allergycheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScanProgressTest {

    @Test
    public void matchingSpendsTheWholeRingOnReading() {
        assertEquals(0, ScanProgress.start(ScanProgress.Step.DECODING, false));
        assertEquals(ScanProgress.MAX, ScanProgress.end(ScanProgress.Step.MATCHING, false));
    }

    @Test
    public void aiModesSaveTheRestOfTheRingForChecks() {
        int checksStart = ScanProgress.start(ScanProgress.Step.AI_CHECKS, true);
        assertEquals(ScanProgress.end(ScanProgress.Step.MATCHING, true), checksStart);
        assertEquals(ScanProgress.MAX, ScanProgress.end(ScanProgress.Step.AI_CHECKS, true));
        assertTrue(checksStart < ScanProgress.MAX / 2);
    }

    @Test
    public void stepsNeverOverlapOrGoBackwards() {
        for (boolean ai : new boolean[] {false, true}) {
            int previousEnd = 0;
            for (ScanProgress.Step step : ScanProgress.Step.values()) {
                if (!ai && step == ScanProgress.Step.AI_CHECKS) {
                    continue;
                }
                assertEquals(previousEnd, ScanProgress.start(step, ai));
                assertTrue(ScanProgress.end(step, ai) >= ScanProgress.start(step, ai));
                previousEnd = ScanProgress.end(step, ai);
            }
            assertEquals(ScanProgress.MAX, previousEnd);
        }
    }

    @Test
    public void aiValueFollowsCompletedChecksAndClamps() {
        int start = ScanProgress.start(ScanProgress.Step.AI_CHECKS, true);
        assertEquals(start, ScanProgress.aiValue(0, 4));
        assertEquals(start + (ScanProgress.MAX - start) / 2, ScanProgress.aiValue(2, 4));
        assertEquals(ScanProgress.MAX, ScanProgress.aiValue(4, 4));
        assertEquals(ScanProgress.MAX, ScanProgress.aiValue(9, 4));
        assertEquals(start, ScanProgress.aiValue(-1, 4));
        assertEquals(start, ScanProgress.aiValue(3, 0));
    }
}
