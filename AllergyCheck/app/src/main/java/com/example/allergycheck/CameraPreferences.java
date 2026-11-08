package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for the capture screen.
 *
 * <p>Its own file rather than a corner of {@code allergy_preferences} or {@code analysis_preferences}:
 * the app already keeps one preferences file per concern, and how the camera behaves is neither an
 * allergen choice nor an analysis one.
 */
final class CameraPreferences {

    private static final String PREFERENCES = "camera_preferences";
    private static final String AUTO_CAPTURE = "auto_capture";

    private CameraPreferences() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * Whether the capture screen takes the photo itself once the ingredient list reads cleanly.
     *
     * <p>Off by default. Auto-capture takes the shutter out of the user's hands, which is something
     * to opt into rather than to discover happening.
     */
    static boolean autoCapture(Context context) {
        return preferences(context).getBoolean(AUTO_CAPTURE, false);
    }

    static void saveAutoCapture(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(AUTO_CAPTURE, enabled).apply();
    }
}
