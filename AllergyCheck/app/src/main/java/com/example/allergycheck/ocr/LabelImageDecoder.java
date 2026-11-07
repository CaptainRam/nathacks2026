package com.example.allergycheck.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.io.IOException;

/**
 * Reads a label photo off a {@link Uri} at a resolution the recogniser can actually use.
 *
 * <p>Handing the camera's output straight to ML Kit leaves recognition quality to chance in both
 * directions: a 12 MP photo of a whole package spends most of its pixels on packaging and costs
 * seconds to process, while a tight crop of a small panel can arrive with glyphs only a handful of
 * pixels tall, below what the Latin model reads reliably. Normalising the long edge into a band
 * fixes both ends.
 *
 * <p>{@link ImageDecoder} applies the EXIF orientation tag itself, so a photo taken in portrait still
 * needs no manual rotation — the same property the pipeline previously relied on
 * {@code InputImage.fromFilePath} for.
 */
public final class LabelImageDecoder {

    /**
     * Below this, small crops are upscaled. Bilinear upscaling invents no detail, but it does put
     * glyph strokes above the recogniser's effective minimum size, and
     * {@link UnsharpMask} restores the edges the interpolation softens.
     */
    @VisibleForTesting
    static final int MIN_LONG_EDGE_PX = 1600;

    /**
     * Above this, photos are downscaled. Caps both the working-set size (an ARGB_8888 bitmap at
     * 2600px plus the luminance buffers) and recognition latency, with no accuracy cost — label type
     * is already resolved well before this.
     */
    @VisibleForTesting
    static final int MAX_LONG_EDGE_PX = 2600;

    private LabelImageDecoder() {
    }

    /**
     * Decodes {@code imageUri} into a software {@link Bitmap} whose long edge lies within
     * {@link #MIN_LONG_EDGE_PX}-{@link #MAX_LONG_EDGE_PX}.
     *
     * <p>Blocking and memory-hungry; call it off the main thread.
     *
     * @throws IOException if the URI cannot be opened or the bytes are not a decodable image
     */
    @WorkerThread
    @NonNull
    public static Bitmap decode(@NonNull Context context, @NonNull Uri imageUri) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), imageUri);
        Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, unusedSource) -> {
            // The preparer reads pixels back, which a hardware bitmap does not allow.
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);

            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            int sampleSize = sampleSizeFor(Math.max(width, height));
            if (sampleSize > 1) {
                // Power-of-two subsampling happens during decode, so the oversized bitmap is never
                // allocated in the first place. The exact scale is finished below.
                decoder.setTargetSampleSize(sampleSize);
            }
        });

        return scaleToBand(decoded);
    }

    /**
     * Largest power-of-two subsampling that keeps the long edge at or above
     * {@link #MAX_LONG_EDGE_PX}, so the follow-up scale only ever has to shrink a little.
     */
    @VisibleForTesting
    static int sampleSizeFor(int longEdgePx) {
        int sampleSize = 1;
        while (longEdgePx / (sampleSize * 2) >= MAX_LONG_EDGE_PX) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    /**
     * Long edge this image should be resized to, or the current long edge when it is already in band
     * and no resize is needed.
     */
    @VisibleForTesting
    static int targetLongEdge(int width, int height) {
        int longEdge = Math.max(width, height);
        if (longEdge > MAX_LONG_EDGE_PX) {
            return MAX_LONG_EDGE_PX;
        }
        if (longEdge > 0 && longEdge < MIN_LONG_EDGE_PX) {
            return MIN_LONG_EDGE_PX;
        }
        return longEdge;
    }

    /** Bilinearly resizes {@code source} into the band, recycling it if a new bitmap is produced. */
    private static Bitmap scaleToBand(@NonNull Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        int target = targetLongEdge(width, height);
        if (target == longEdge || longEdge == 0) {
            return source;
        }

        float scale = (float) target / longEdge;
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, /* filter= */ true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }
}
