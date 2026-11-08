package com.example.allergycheck.ocr;

/**
 * Edge sharpening for grayscale label photos.
 *
 * <p>Two things soften the small type on an ingredients panel before the recogniser ever sees it:
 * the phone's JPEG encoder, which smooths exactly the fine detail that distinguishes one character
 * from another, and any upscaling applied to a tightly cropped panel. Adding back a scaled copy of
 * the difference between the image and a blurred version of itself recovers the stroke edges.
 *
 * <p>Pure integer maths on luminance arrays, deliberately free of any {@code android.graphics} type
 * so it can be unit-tested without a device.
 */
final class UnsharpMask {

    /**
     * How much of the detail layer to add back. The strongest amount that did not read worse than no
     * sharpening at all on any label measured; see {@link LabelImagePreparer} for the numbers and for
     * why sharpening is not applied by default.
     */
    static final float SUGGESTED_AMOUNT = 0.8f;

    /** Separable 5-tap binomial approximation of a Gaussian; {@code sum == KERNEL_SUM}. */
    private static final int[] KERNEL = {1, 4, 6, 4, 1};
    private static final int KERNEL_SUM = 16;
    private static final int KERNEL_RADIUS = 2;

    private UnsharpMask() {
    }

    /**
     * Returns a sharpened copy of {@code luminance}; the input is left untouched.
     *
     * <p>Edges are handled by clamping the sample position to the image, which keeps the border
     * brightness stable instead of darkening it towards an imaginary black surround.
     *
     * @param luminance row-major luminance values in 0-255, length {@code width * height}
     * @param amount    strength of the detail layer; 0 returns an unchanged copy
     */
    static int[] apply(int[] luminance, int width, int height, float amount) {
        int[] sharpened = new int[luminance.length];
        if (width <= 0 || height <= 0) {
            return sharpened;
        }

        int[] blurred = blur(luminance, width, height);
        for (int index = 0; index < luminance.length; index++) {
            int value = Math.round(luminance[index] + amount * (luminance[index] - blurred[index]));
            sharpened[index] = value < 0 ? 0 : (value > 255 ? 255 : value);
        }
        return sharpened;
    }

    /** Two passes of the 1-D kernel — horizontal then vertical — via one intermediate buffer. */
    private static int[] blur(int[] luminance, int width, int height) {
        int[] horizontal = new int[luminance.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int sum = 0;
                for (int tap = -KERNEL_RADIUS; tap <= KERNEL_RADIUS; tap++) {
                    sum += KERNEL[tap + KERNEL_RADIUS] * luminance[row + clamp(x + tap, width)];
                }
                horizontal[row + x] = sum / KERNEL_SUM;
            }
        }

        int[] blurred = new int[luminance.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int sum = 0;
                for (int tap = -KERNEL_RADIUS; tap <= KERNEL_RADIUS; tap++) {
                    sum += KERNEL[tap + KERNEL_RADIUS] * horizontal[clamp(y + tap, height) * width + x];
                }
                blurred[row + x] = sum / KERNEL_SUM;
            }
        }
        return blurred;
    }

    private static int clamp(int coordinate, int size) {
        if (coordinate < 0) {
            return 0;
        }
        return coordinate >= size ? size - 1 : coordinate;
    }
}
