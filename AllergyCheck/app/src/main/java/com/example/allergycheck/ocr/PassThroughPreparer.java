package com.example.allergycheck.ocr;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

/**
 * Recognises the photo exactly as decoded. The baseline to compare {@link LabelImagePreparer}
 * against when judging whether preparation helps a particular label.
 */
public final class PassThroughPreparer implements ImagePreparer {

    @NonNull
    @Override
    public Bitmap prepare(@NonNull Bitmap source) {
        return source;
    }
}
