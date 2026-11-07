package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class AllergenPreferences {
    private static final String PREFERENCES = "allergy_preferences";
    private static final String ALLERGENS = "selected_allergens";

    private AllergenPreferences() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static Set<String> selectedAllergens(Context context) {
        return new HashSet<>(preferences(context).getStringSet(ALLERGENS,
                Collections.emptySet()));
    }

    static void saveSelectedAllergens(Context context, Set<String> selected) {
        preferences(context).edit().putStringSet(ALLERGENS, new HashSet<>(selected)).apply();
    }
}
