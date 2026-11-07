package com.example.allergycheck.ocr;

import android.graphics.Rect;

import androidx.annotation.Nullable;

/**
 * One line of text recognised on a label, together with where it sat on the image.
 *
 * <p>The positional data is kept deliberately: a filter that wants to isolate the ingredients
 * panel needs to know how lines are laid out, not just what they say. {@link #text()} is the
 * untouched recogniser output so it can be quoted back to the user as label evidence.
 */
public final class OcrLine {

    private final String text;
    @Nullable
    private final Rect boundingBox;
    private final int blockIndex;
    private final int lineIndex;
    private final float angle;

    public OcrLine(String text, @Nullable Rect boundingBox, int blockIndex, int lineIndex, float angle) {
        this.text = text;
        this.boundingBox = boundingBox;
        this.blockIndex = blockIndex;
        this.lineIndex = lineIndex;
        this.angle = angle;
    }

    /** The recognised text, exactly as returned by the recogniser. */
    public String text() {
        return text;
    }

    /** Bounds in image pixel coordinates, or {@code null} if the recogniser did not supply them. */
    @Nullable
    public Rect boundingBox() {
        return boundingBox;
    }

    /** Index of the text block this line belongs to, in recogniser order. */
    public int blockIndex() {
        return blockIndex;
    }

    /** Index of this line within its block. */
    public int lineIndex() {
        return lineIndex;
    }

    /** Rotation of the line in degrees, useful for discarding text running up the side of a package. */
    public float angle() {
        return angle;
    }

    @Override
    public String toString() {
        return "OcrLine{" + blockIndex + ":" + lineIndex + " \"" + text + "\"}";
    }
}
