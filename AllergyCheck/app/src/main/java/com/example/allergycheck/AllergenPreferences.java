package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.allergycheck.analysis.Allergen;

import java.util.Collections;
import java.util.EnumSet;
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

    /**
     * The saved allergens as the analysis engine's enum, in enum order. Names the engine does not
     * know (an option added to the picker but not to {@link Allergen}) are skipped, so a stale
     * preference cannot break a scan.
     */
    static EnumSet<Allergen> selectedAllergenSet(Context context) {
        EnumSet<Allergen> allergens = EnumSet.noneOf(Allergen.class);
        for (String label : selectedAllergens(context)) {
            Allergen allergen = Allergen.fromLabel(label);
            if (allergen != null) {
                allergens.add(allergen);
            }
        }
        return allergens;
    }

    static void saveSelectedAllergens(Context context, Set<String> selected) {
        preferences(context).edit().putStringSet(ALLERGENS, new HashSet<>(selected)).apply();
    }
}
