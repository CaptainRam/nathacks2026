package com.example.allergycheck.ocr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class UnsharpMaskTest {

    private static final int WIDTH = 7;
    private static final int HEIGHT = 7;
    private static final int CENTRE = (HEIGHT / 2) * WIDTH + (WIDTH / 2);

    @Test
    public void flatFieldComesBackUnchanged() {
        int[] flat = new int[WIDTH * HEIGHT];
        Arrays.fill(flat, 128);

        assertArrayEquals(flat, UnsharpMask.apply(flat, WIDTH, HEIGHT, UnsharpMask.SUGGESTED_AMOUNT));
    }

    @Test
    public void localContrastIsIncreasedAroundAnEdge() {
        // A single light mark on a mid-grey page: the mark should brighten and the ring around it
        // should darken. That widened gap is what makes a thin stroke legible to the recogniser.
        int[] image = new int[WIDTH * HEIGHT];
        Arrays.fill(image, 128);
        image[CENTRE] = 200;

        int[] sharpened = UnsharpMask.apply(image, WIDTH, HEIGHT, UnsharpMask.SUGGESTED_AMOUNT);

        assertTrue("the mark should brighten, was " + sharpened[CENTRE], sharpened[CENTRE] > 200);
        assertTrue("the pixel beside it should darken, was " + sharpened[CENTRE + 1],
                sharpened[CENTRE + 1] < 128);
        assertTrue("the pixel above it should darken, was " + sharpened[CENTRE - WIDTH],
                sharpened[CENTRE - WIDTH] < 128);
    }

    @Test
    public void resultStaysInRange() {
        // Maximum-contrast input drives the detail layer well past both ends of the range.
        int[] image = new int[WIDTH * HEIGHT];
        for (int index = 0; index < image.length; index++) {
            image[index] = (index % 2 == 0) ? 0 : 255;
        }

        int[] sharpened = UnsharpMask.apply(image, WIDTH, HEIGHT, 4f);

        for (int value : sharpened) {
            assertTrue("out of range: " + value, value >= 0 && value <= 255);
        }
    }

    @Test
    public void zeroAmountCopiesTheInput() {
        int[] image = new int[WIDTH * HEIGHT];
        for (int index = 0; index < image.length; index++) {
            image[index] = index * 3 % 256;
        }

        assertArrayEquals(image, UnsharpMask.apply(image, WIDTH, HEIGHT, 0f));
    }

    @Test
    public void degenerateDimensionsDoNotOverrun() {
        // The kernel radius exceeds these images in one or both directions; edge clamping must cope.
        assertEquals(1, UnsharpMask.apply(new int[]{90}, 1, 1, UnsharpMask.SUGGESTED_AMOUNT).length);
        assertEquals(3, UnsharpMask.apply(new int[]{10, 200, 10}, 3, 1, UnsharpMask.SUGGESTED_AMOUNT).length);
        assertEquals(3, UnsharpMask.apply(new int[]{10, 200, 10}, 1, 3, UnsharpMask.SUGGESTED_AMOUNT).length);
        assertEquals(0, UnsharpMask.apply(new int[0], 0, 0, UnsharpMask.SUGGESTED_AMOUNT).length);
    }
}
