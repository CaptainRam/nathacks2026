package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    /**
     * The saved allergens as the analysis engine's objects, in dictionary order. Preferences are
     * keyed on the user-facing label; one the dictionary does not know (saved by an older build,
     * or dropped from the dictionary since) is skipped, so a stale preference cannot break a scan.
     */
    static Set<Allergen> selectedAllergenSet(Context context, AllergenDictionary dictionary) {
        Set<String> saved = selectedAllergens(context);
        Set<Allergen> allergens = new LinkedHashSet<>();
        for (Allergen allergen : dictionary.allergens()) {
            if (saved.contains(allergen.label())) {
                allergens.add(allergen);
            }
        }
        return allergens;
    }

    static void saveSelectedAllergens(Context context, Set<String> selected) {
        preferences(context).edit().putStringSet(ALLERGENS, new HashSet<>(selected)).apply();
    }
}
