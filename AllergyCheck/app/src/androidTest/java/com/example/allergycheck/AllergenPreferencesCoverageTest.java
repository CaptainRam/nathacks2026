package com.example.allergycheck;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Missing preference migration/pruning cases from analysis: SE-05, SE-06, SE-08, SE-09. */
@RunWith(AndroidJUnit4.class)
public class AllergenPreferencesCoverageTest {
    private static final String KEY = "selected_allergens";
    private Context context;
    private SharedPreferences preferences;
    private AllergenDictionary dictionary;
    private boolean hadSelection;
    private Set<String> previous;

    @Before
    public void isolateSelection() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dictionary = AllergenDictionaryProvider.get(context);
        preferences = AllergenPreferences.preferences(context);
        hadSelection = preferences.contains(KEY);
        previous = new HashSet<>(preferences.getStringSet(KEY, Collections.emptySet()));
        assertTrue(preferences.edit().remove(KEY).commit());
    }

    @After
    public void restoreSelection() {
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        if (hadSelection) editor.putStringSet(KEY, previous);
        else editor.remove(KEY);
        assertTrue(editor.commit());
    }

    @Test
    public void legacyLabelsMigrateToStableIdsAndKeepTheSameCount() {
        store(" tree NUTS ", "Milk");

        Set<Allergen> selected = AllergenPreferences.selectedAllergenSet(context, dictionary);

        assertEquals(2, selected.size());
        assertEquals(set("MILK", "TREE_NUTS"),
                AllergenPreferences.selectedAllergenIds(context, dictionary));
        assertEquals(set("MILK", "TREE_NUTS"), preferences.getStringSet(KEY, null));
    }

    @Test
    public void unknownValuesArePrunedAndLabelIdDuplicatesCollapse() {
        store("MILK", " milk ", "REMOVED_ALLERGEN", "unknown label");

        assertEquals(Collections.singleton(dictionary.byId("MILK")),
                AllergenPreferences.selectedAllergenSet(context, dictionary));
        assertEquals(set("MILK"), preferences.getStringSet(KEY, null));
        // A second read must not bring the obsolete selection back.
        assertEquals(set("MILK"), AllergenPreferences.selectedAllergenIds(context, dictionary));
    }

    @Test
    public void selectionsAndIdsUseDictionaryOrderRegardlessOfStoredOrder() {
        Allergen first = dictionary.allergens().get(0);
        Allergen last = dictionary.allergens().get(dictionary.allergens().size() - 1);
        store(last.id(), first.id());

        assertEquals(Arrays.asList(first, last), new ArrayList<>(
                AllergenPreferences.selectedAllergenSet(context, dictionary)));
        assertEquals(Arrays.asList(first.id(), last.id()), new ArrayList<>(
                AllergenPreferences.selectedAllergenIds(context, dictionary)));
    }

    @Test
    public void savedIdsAreCopiedAndReloadedThroughANewContext() throws Exception {
        Set<String> draft = set("MILK", "PEANUTS");
        AllergenPreferences.saveSelectedAllergenIds(context, draft);
        draft.clear();
        // commit flushes the preceding apply before checking the persisted selection.
        assertTrue(preferences.edit().commit());
        Context reloaded = context.createPackageContext(context.getPackageName(), 0);

        assertEquals(set("MILK", "PEANUTS"),
                AllergenPreferences.selectedAllergenIds(reloaded, dictionary));
        Set<String> returned = AllergenPreferences.selectedAllergenIds(reloaded, dictionary);
        returned.clear();
        assertEquals(set("MILK", "PEANUTS"),
                AllergenPreferences.selectedAllergenIds(reloaded, dictionary));
    }

    @Test
    public void absentOrEntirelyObsoleteSelectionResolvesToEmpty() {
        assertTrue(AllergenPreferences.selectedAllergenSet(context, dictionary).isEmpty());
        store("REMOVED_ALLERGEN");
        assertTrue(AllergenPreferences.selectedAllergenSet(context, dictionary).isEmpty());
        assertEquals(Collections.emptySet(), preferences.getStringSet(KEY, null));
    }

    private void store(String... values) {
        assertTrue(preferences.edit().putStringSet(KEY, set(values)).commit());
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
