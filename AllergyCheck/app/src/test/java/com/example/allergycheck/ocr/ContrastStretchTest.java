package com.example.allergycheck.ocr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContrastStretchTest {

    private static int[] histogramOf(int... valueCountPairs) {
        int[] histogram = new int[256];
        for (int index = 0; index < valueCountPairs.length; index += 2) {
            histogram[valueCountPairs[index]] += valueCountPairs[index + 1];
        }
        return histogram;
    }

    private static int totalOf(int[] histogram) {
        int total = 0;
        for (int count : histogram) {
            total += count;
        }
        return total;
    }

    @Test
    public void narrowBandIsStretchedToTheFullRange() {
        // The grey-on-grey case the stretch exists for: everything between 100 and 140.
        int[] histogram = new int[256];
        for (int value = 100; value <= 140; value++) {
            histogram[value] = 100;
        }

        int[] bounds = ContrastStretch.percentileBounds(histogram, totalOf(histogram), 0.01f, 0.99f);
        int[] lut = ContrastStretch.buildLut(bounds[0], bounds[1]);

        assertTrue("black point should sit inside the band", bounds[0] >= 100 && bounds[0] <= 110);
        assertTrue("white point should sit inside the band", bounds[1] >= 130 && bounds[1] <= 140);
        assertEquals(0, lut[bounds[0]]);
        assertEquals(255, lut[bounds[1]]);
    }

    @Test
    public void outliersDoNotDefineTheMapping() {
        // One black speck and one glare highlight against a mid-grey page. Using the absolute min and
        // max would make the stretch a no-op; percentiles must ignore them.
        int[] histogram = histogramOf(0, 1, 120, 5_000, 180, 5_000, 255, 1);

        int[] bounds = ContrastStretch.percentileBounds(histogram, totalOf(histogram), 0.01f, 0.99f);

        assertEquals(120, bounds[0]);
        assertEquals(180, bounds[1]);
    }

    @Test
    public void flatImageIsLeftAlone() {
        int[] histogram = histogramOf(42, 10_000);

        int[] bounds = ContrastStretch.percentileBounds(histogram, totalOf(histogram), 0.01f, 0.99f);

        assertEquals(bounds[0], bounds[1]);
        assertArrayEquals("amplifying the noise of a flat image only costs accuracy",
                identityLut(), ContrastStretch.buildLut(bounds[0], bounds[1]));
    }

    @Test
    public void invertedBoundsFallBackToIdentity() {
        assertArrayEquals(identityLut(), ContrastStretch.buildLut(200, 50));
    }

    @Test
    public void emptyHistogramFallsBackToTheFullRange() {
        int[] bounds = ContrastStretch.percentileBounds(new int[256], 0, 0.01f, 0.99f);

        assertEquals(0, bounds[0]);
        assertEquals(255, bounds[1]);
    }

    @Test
    public void lutIsMonotonicAndClamped() {
        int[] lut = ContrastStretch.buildLut(60, 200);

        assertEquals(0, lut[0]);
        assertEquals(0, lut[60]);
        assertEquals(255, lut[200]);
        assertEquals(255, lut[255]);
        for (int value = 1; value < lut.length; value++) {
            assertTrue("lut must never decrease at " + value, lut[value] >= lut[value - 1]);
            assertTrue("lut must stay in range at " + value, lut[value] >= 0 && lut[value] <= 255);
        }
    }

    private static int[] identityLut() {
        int[] identity = new int[256];
        for (int value = 0; value < identity.length; value++) {
            identity[value] = value;
        }
        return identity;
    }
}
