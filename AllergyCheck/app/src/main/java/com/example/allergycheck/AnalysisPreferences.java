package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent detection configuration. Credentials are kept out of Android backups. */
public final class AnalysisPreferences {
    public enum Mode { MATCHING, LOCAL_AI, OPENROUTER, DECISIONS }

    public static final int DEFAULT_CLOUD_CONCURRENCY = 1;
    public static final int MIN_CLOUD_CONCURRENCY = 1;

    private static final String CONFIGURATION = "analysis_preferences";
    private static final String CREDENTIALS = "analysis_credentials";

    private AnalysisPreferences() { }

    private static SharedPreferences configuration(Context context) {
        return context.getSharedPreferences(CONFIGURATION, Context.MODE_PRIVATE);
    }

    public static Mode mode(Context context) {
        try {
            return Mode.valueOf(configuration(context).getString("mode", Mode.MATCHING.name()));
        } catch (IllegalArgumentException exception) {
            return Mode.MATCHING;
        }
    }

    public static void saveMode(Context context, Mode mode) {
        configuration(context).edit().putString("mode", mode.name()).apply();
    }

    public static String openRouterApiKey(Context context) {
        return context.getSharedPreferences(CREDENTIALS, Context.MODE_PRIVATE)
                .getString("openrouter_api_key", "");
    }

    public static void saveOpenRouterApiKey(Context context, String key) {
        context.getSharedPreferences(CREDENTIALS, Context.MODE_PRIVATE).edit()
                .putString("openrouter_api_key", key.trim()).apply();
    }

    public static String openRouterModel(Context context) {
        return configuration(context).getString("openrouter_model", "");
    }

    public static void saveOpenRouterModel(Context context, String model) {
        configuration(context).edit().putString("openrouter_model", model.trim()).apply();
    }

    public static String decisionsModel(Context context) {
        return configuration(context).getString("decisions_model", "");
    }

    public static void saveDecisionsModel(Context context, String model) {
        configuration(context).edit().putString("decisions_model", model.trim()).apply();
    }

    public static double decisionsThreshold(Context context) {
        try {
            double threshold = Double.parseDouble(configuration(context)
                    .getString("decisions_threshold", "0.5"));
            return Double.isFinite(threshold) && threshold >= 0 && threshold <= 1
                    ? threshold : 0.5;
        } catch (NumberFormatException exception) {
            return 0.5;
        }
    }

    public static void saveDecisionsThreshold(Context context, double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Threshold must be between 0 and 1");
        }
        configuration(context).edit().putString("decisions_threshold", Double.toString(threshold)).apply();
    }

    /** Maximum simultaneous requests shared by OpenRouter LLM and Decisions modes. */
    public static int cloudConcurrency(Context context) {
        try {
            int concurrency = Integer.parseInt(configuration(context)
                    .getString("cloud_concurrency", "1"));
            return concurrency >= MIN_CLOUD_CONCURRENCY
                    ? concurrency : DEFAULT_CLOUD_CONCURRENCY;
        } catch (NumberFormatException | ClassCastException exception) {
            return DEFAULT_CLOUD_CONCURRENCY;
        }
    }

    public static void saveCloudConcurrency(Context context, int concurrency) {
        if (concurrency < MIN_CLOUD_CONCURRENCY) {
            throw new IllegalArgumentException("Cloud concurrency must be positive");
        }
        configuration(context).edit().putString("cloud_concurrency", Integer.toString(concurrency)).apply();
    }
}
