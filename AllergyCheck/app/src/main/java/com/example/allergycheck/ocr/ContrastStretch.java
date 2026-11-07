package com.example.allergycheck.ocr;

/**
 * Per-image contrast normalisation for grayscale label photos.
 *
 * <p>Ingredient panels are routinely printed grey-on-grey and photographed under shop lighting, so
 * the luminance actually present in the image can occupy a small slice of the 0-255 range. Stretching
 * that slice back out is the single cheapest way to recover characters the recogniser would otherwise
 * drop.
 *
 * <p>The black and white points are taken from percentiles rather than the absolute min and max so
 * that one glare highlight or one dark speck cannot define the whole mapping.
 *
 * <p>Pure integer maths on histograms and lookup tables, deliberately free of any
 * {@code android.graphics} type so it can be unit-tested without a device.
 */
final class ContrastStretch {

    /** Fraction of the darkest pixels mapped to black. */
    static final float DEFAULT_LOW_FRACTION = 0.01f;

    /** Fraction of the image at or below the white point; the brightest 1% is clipped. */
    static final float DEFAULT_HIGH_FRACTION = 0.99f;

    private ContrastStretch() {
    }

    /**
     * Finds the luminance values at the two requested percentiles of {@code histogram}.
     *
     * @param histogram     256 bins of pixel counts, bin index == luminance
     * @param pixelCount    total pixels the histogram was built from
     * @param lowFraction   percentile mapped to black, as a fraction in [0, 1]
     * @param highFraction  percentile mapped to white, as a fraction in [0, 1]
     * @return {@code {blackPoint, whitePoint}}. The pair can be degenerate (equal, or inverted on a
     *         single-value image); {@link #buildLut} treats that as "leave the image alone" rather
     *         than failing, so callers do not need to special-case it.
     */
    static int[] percentileBounds(int[] histogram, int pixelCount, float lowFraction, float highFraction) {
        if (pixelCount <= 0) {
            return new int[]{0, 255};
        }

        int lowTarget = (int) Math.floor(lowFraction * pixelCount);
        int highTarget = (int) Math.ceil(highFraction * pixelCount);

        int blackPoint = 0;
        int whitePoint = 255;
        int cumulative = 0;
        boolean blackFound = false;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (!blackFound && cumulative > lowTarget) {
                blackPoint = value;
                blackFound = true;
            }
            if (cumulative >= highTarget) {
                whitePoint = value;
                break;
            }
        }
        return new int[]{blackPoint, whitePoint};
    }

    /**
     * Builds the 256-entry map that sends {@code blackPoint} to 0 and {@code whitePoint} to 255,
     * clamping everything outside that span.
     *
     * <p>Returns an identity map when the span is empty or inverted — a flat image has no contrast to
     * stretch, and amplifying its noise would only cost the recogniser.
     */
    static int[] buildLut(int blackPoint, int whitePoint) {
        int[] lut = new int[256];
        if (whitePoint <= blackPoint) {
            for (int value = 0; value < lut.length; value++) {
                lut[value] = value;
            }
            return lut;
        }

        float scale = 255f / (whitePoint - blackPoint);
        for (int value = 0; value < lut.length; value++) {
            int mapped = Math.round((value - blackPoint) * scale);
            lut[value] = mapped < 0 ? 0 : (mapped > 255 ? 255 : mapped);
        }
        return lut;
    }
}
