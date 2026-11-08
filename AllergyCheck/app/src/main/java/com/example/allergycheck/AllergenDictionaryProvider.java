package com.example.allergycheck;

import android.content.Context;

import androidx.annotation.NonNull;

import com.example.allergycheck.analysis.AllergenDictionary;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the shipped allergen dictionary from {@code res/raw} once and keeps it.
 *
 * <p>This is the only place that knows the dictionary is an Android resource, which is what lets
 * the analysis package stay free of Android imports and unit-testable.
 */
final class AllergenDictionaryProvider {

    private static AllergenDictionary cached;

    private AllergenDictionaryProvider() {
    }

    /**
     * The shipped dictionary. {@code synchronized} because analysis runs on a worker thread while
     * the pickers read it on the main thread.
     *
     * @throws IllegalStateException if the resource is missing or malformed — that is a packaging
     *     bug, and an empty dictionary would quietly turn every scan into "no allergens selected"
     */
    @NonNull
    static synchronized AllergenDictionary get(@NonNull Context context) {
        if (cached == null) {
            try (InputStream in = context.getResources().openRawResource(R.raw.allergen_dictionary)) {
                cached = AllergenDictionary.load(in);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("allergen_dictionary.json is missing or invalid", e);
            }
        }
        return cached;
    }
}
