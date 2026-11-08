package com.example.allergycheck.ocr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

/**
 * Turns a label photo into the grayscale, contrast-normalised, edge-sharpened image that reads best.
 *
 * <p>Two passes by default:
 * <ol>
 *   <li><b>Grayscale.</b> Colour carries no signal for printed text and would triple the work below.</li>
 *   <li><b>Contrast stretch</b> ({@link ContrastStretch}). Ingredient panels are commonly printed
 *       grey-on-grey and photographed in poor light, so the luminance actually present occupies a
 *       narrow band. Stretching it back out is where the accuracy comes from.</li>
 * </ol>
 *
 * <p><b>Sharpening is available but off by default</b>, on measurement rather than principle.
 * Levenshtein distance from the recognised text to ground truth, over five synthetic labels on a
 * Pixel 10 (lower is better; see {@code OcrPipelineTest} for the fixtures):
 *
 * <pre>
 * label                              as decoded   contrast   contrast+sharpen(0.8)
 * grey-on-grey, small                    14          15               14
 * dark, flat                              8           3                4
 * small, good contrast                   11          12               11
 * blurred, good contrast                  4           4                2
 * blurred and flat                       19           8               17
 * total                                  56          42               48
 * </pre>
 *
 * Contrast alone cuts the error by a quarter. Sharpening on top only pays on the blurred
 * <em>high-contrast</em> label, and it gives back most of the contrast win on the blurred flat one —
 * the stretch amplifies JPEG noise, and the mask then amplifies that. Pass an amount to
 * {@link #LabelImagePreparer(float)} if a future capture path makes blur the dominant problem.
 *
 * <p><b>Deliberately not binarised.</b> Otsu or adaptive thresholding is the obvious next step and it
 * reliably reads worse here: the recogniser is trained on natural grayscale imagery, and a hard
 * threshold erases the thin, broken strokes of 5pt type on textured or glossy plastic — exactly the
 * characters that were marginal to begin with.
 */
public final class LabelImagePreparer implements ImagePreparer {

    private final float sharpenAmount;

    /** Grayscale and contrast stretch, no sharpening — the configuration that measured best. */
    public LabelImagePreparer() {
        this(0f);
    }

    /**
     * @param sharpenAmount strength of the unsharp mask; 0 skips it.
     *                      {@link UnsharpMask#SUGGESTED_AMOUNT} is the strongest value that did not
     *                      read worse than no sharpening at all on any label measured.
     */
    public LabelImagePreparer(float sharpenAmount) {
        this.sharpenAmount = sharpenAmount;
    }

    @NonNull
    @Override
    public Bitmap prepare(@NonNull Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return source;
        }

        // One full-frame int[] carries the frame through every pass: the packed pixels become
        // luminance in place, the stretch is applied in place over that, and the packed result is
        // written back over it again. A label photo is megapixels, so each array that does not have
        // to exist is tens of megabytes of peak footprint alongside source and the output bitmap.
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] histogram = new int[256];
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            // BT.601 weights in fixed point: the same luma the recogniser's own preprocessing assumes.
            int value = (77 * ((pixel >> 16) & 0xFF)
                    + 150 * ((pixel >> 8) & 0xFF)
                    + 29 * (pixel & 0xFF)) >> 8;
            pixels[index] = value;
            histogram[value]++;
        }

        int[] bounds = ContrastStretch.percentileBounds(
                histogram, pixels.length,
                ContrastStretch.DEFAULT_LOW_FRACTION, ContrastStretch.DEFAULT_HIGH_FRACTION);
        int[] lut = ContrastStretch.buildLut(bounds[0], bounds[1]);
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = lut[pixels[index]];
        }

        // Sharpening is the one pass that cannot work in place — the mask reads neighbours of the
        // unsharpened image — but it allocates its own destination, so the second buffer exists only
        // when sharpening actually runs.
        int[] luminance = sharpenAmount == 0f
                ? pixels
                : UnsharpMask.apply(pixels, width, height, sharpenAmount);

        for (int index = 0; index < pixels.length; index++) {
            // Safe even when luminance is pixels: index is read before it is overwritten.
            int value = luminance[index];
            pixels[index] = 0xFF000000 | (value << 16) | (value << 8) | value;
        }

        Bitmap prepared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        prepared.setPixels(pixels, 0, width, 0, 0, width, height);
        return prepared;
    }
}
