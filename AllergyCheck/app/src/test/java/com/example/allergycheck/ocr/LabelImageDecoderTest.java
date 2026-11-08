package com.example.allergycheck.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LabelImageDecoderTest {

    @Test
    public void oversizedPhotosComeDownToTheCeiling() {
        assertEquals(LabelImageDecoder.MAX_LONG_EDGE_PX, LabelImageDecoder.targetLongEdge(4000, 3000));
        assertEquals(LabelImageDecoder.MAX_LONG_EDGE_PX, LabelImageDecoder.targetLongEdge(3000, 4000));
    }

    @Test
    public void smallCropsAreBroughtUpToTheFloor() {
        // The case that motivates upscaling at all: a tight crop of a small ingredients panel.
        assertEquals(LabelImageDecoder.MIN_LONG_EDGE_PX, LabelImageDecoder.targetLongEdge(800, 600));
    }

    @Test
    public void imagesAlreadyInBandAreNotResized() {
        assertEquals(2000, LabelImageDecoder.targetLongEdge(2000, 1500));
        assertEquals(2000, LabelImageDecoder.targetLongEdge(2000, 2000));
        assertEquals(LabelImageDecoder.MIN_LONG_EDGE_PX,
                LabelImageDecoder.targetLongEdge(LabelImageDecoder.MIN_LONG_EDGE_PX, 1200));
        assertEquals(LabelImageDecoder.MAX_LONG_EDGE_PX,
                LabelImageDecoder.targetLongEdge(LabelImageDecoder.MAX_LONG_EDGE_PX, 1200));
    }

    @Test
    public void emptyBoundsAskForNoResize() {
        assertEquals(0, LabelImageDecoder.targetLongEdge(0, 0));
    }

    @Test
    public void subsamplingStopsWhileStillAboveTheCeiling() {
        // Subsampling must never undershoot: the exact scale afterwards should only ever shrink.
        for (int longEdge : new int[]{640, 1600, 2600, 5200, 8000, 12000}) {
            int sampled = longEdge / LabelImageDecoder.sampleSizeFor(longEdge);
            assertTrue("undershot for " + longEdge + ": " + sampled,
                    sampled >= Math.min(longEdge, LabelImageDecoder.MAX_LONG_EDGE_PX));
        }
    }

    @Test
    public void subsamplingIsSkippedForImagesNearTheCeiling() {
        assertEquals(1, LabelImageDecoder.sampleSizeFor(800));
        assertEquals(1, LabelImageDecoder.sampleSizeFor(LabelImageDecoder.MAX_LONG_EDGE_PX));
        assertEquals(4, LabelImageDecoder.sampleSizeFor(12000));
    }
}
