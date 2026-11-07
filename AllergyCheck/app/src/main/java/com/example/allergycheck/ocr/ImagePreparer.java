package com.example.allergycheck.ocr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

/**
 * Conditions a decoded label photo before recognition runs on it.
 *
 * <p>The input-side twin of {@link TextFilter}: where a filter narrows what came back from the
 * recogniser, a preparer improves what goes in. ML Kit's Latin recogniser exposes no quality
 * settings, so the image is the only place recognition accuracy can be influenced at all.
 *
 * <p>{@link OcrPipeline} applies exactly one preparer to every image, so swapping
 * {@link LabelImagePreparer} for {@link PassThroughPreparer} is a one-line change at the call site —
 * useful for measuring whether preparation is actually helping on a given photo.
 *
 * <p>Implementations must be pure: same pixels in, same pixels out, no I/O. They run off the main
 * thread and must not recycle or mutate {@code source}; the caller owns it.
 */
public interface ImagePreparer {

    /**
     * Returns the image to recognise. May return {@code source} itself when there is nothing to do.
     *
     * <p>The result must be a software {@link Bitmap} in {@link Bitmap.Config#ARGB_8888}, which is
     * what {@code InputImage.fromBitmap} accepts.
     */
    @NonNull
    Bitmap prepare(@NonNull Bitmap source);
}
