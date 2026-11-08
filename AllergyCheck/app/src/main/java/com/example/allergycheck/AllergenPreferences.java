package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The allergens the user chose to be warned about, stored as {@link Allergen#id()} values.
 *
 * <p>Ids rather than labels. A label is display text that can be reworded or recased in the
 * dictionary; a stored selection that no longer matches one is dropped from the scan, and since it
 * has no checkbox in the pickers the user cannot even clear it. Keying on the id the dictionary
 * promises is stable means rewording "Tree nuts" can never quietly stop checking for tree nuts.
 *
 * <p>Every read resolves through the dictionary, so the count the home screen shows, the chips it
 * draws, the boxes the pickers tick and the set the analyser receives are always the same
 * allergens — there is no second, unfiltered source to drift away from.
 */
final class AllergenPreferences {
    private static final String PREFERENCES = "allergy_preferences";
    private static final String ALLERGENS = "selected_allergens";
    private static final String SHOW_NOT_DETECTED = "show_not_detected";
    private static final String MAY_CONTAIN_RED_WARNING = "may_contain_red_warning";

    private AllergenPreferences() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * The chosen allergens, in dictionary order.
     *
     * <p>Doubles as the migration and pruning step. Builds before this keyed the selection on the
     * user-facing label, so each stored value is resolved as an id first and then, ignoring case
     * and surrounding space, as a label. Anything that resolves to neither is dropped and the
     * cleaned set is written back, so a preference the dictionary no longer defines cannot linger
     * and inflate the "N allergens selected" count past what is actually being checked.
     */
    static Set<Allergen> selectedAllergenSet(Context context, AllergenDictionary dictionary) {
        Set<String> stored = new HashSet<>(
                preferences(context).getStringSet(ALLERGENS, Collections.emptySet()));
        Set<Allergen> resolved = new HashSet<>();
        for (String value : stored) {
            Allergen allergen = dictionary.byId(value);
            if (allergen == null) {
                allergen = dictionary.byLabel(value);
            }
            if (allergen != null) {
                resolved.add(allergen);
            }
        }
        // Rebuilt in dictionary order so results and pickers list allergens the same way.
        Set<Allergen> ordered = new LinkedHashSet<>();
        for (Allergen allergen : dictionary.allergens()) {
            if (resolved.contains(allergen)) {
                ordered.add(allergen);
            }
        }
        Set<String> ids = idsOf(ordered);
        if (!ids.equals(stored)) {
            saveSelectedAllergenIds(context, ids);
        }
        return ordered;
    }

    /** The chosen allergens as ids, in dictionary order. Resolved exactly as above. */
    static Set<String> selectedAllergenIds(Context context, AllergenDictionary dictionary) {
        return idsOf(selectedAllergenSet(context, dictionary));
    }

    /** Replaces the selection. {@code ids} must be {@link Allergen#id()} values. */
    static void saveSelectedAllergenIds(Context context, Set<String> ids) {
        preferences(context).edit().putStringSet(ALLERGENS, new HashSet<>(ids)).apply();
    }

    /**
     * Whether the result lists selected allergens that were not found on the label. Off by
     * default, so the result shows only what the food (may) contain.
     */
    static boolean showNotDetected(Context context) {
        return preferences(context).getBoolean(SHOW_NOT_DETECTED, false);
    }

    static void saveShowNotDetected(Context context, boolean show) {
        preferences(context).edit().putBoolean(SHOW_NOT_DETECTED, show).apply();
    }

    /** Use a red warning for precautionary findings unless the user chooses yellow. */
    static boolean mayContainRedWarning(Context context) {
        return preferences(context).getBoolean(MAY_CONTAIN_RED_WARNING, true);
    }

    static void saveMayContainRedWarning(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(MAY_CONTAIN_RED_WARNING, enabled).apply();
    }

    private static Set<String> idsOf(Set<Allergen> allergens) {
        Set<String> ids = new LinkedHashSet<>();
        for (Allergen allergen : allergens) {
            ids.add(allergen.id());
        }
        return ids;
    }
}
