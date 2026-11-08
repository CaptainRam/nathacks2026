package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Guards the dictionary data itself, so a hand-edit that breaks the schema or smuggles in an
 * unmatched phrase fails the build instead of quietly losing a term on a user's phone.
 */
public class AllergenDictionaryTest {

    private static final AllergenDictionary DICT = TestDictionaries.shipped();

    private static final List<String> EXPECTED_IDS = Arrays.asList(
            "MILK", "EGGS", "PEANUTS", "TREE_NUTS", "SOY", "WHEAT", "SESAME", "FISH", "SHELLFISH",
            "MUSTARD");

    private static final List<String> EXPECTED_LABELS = Arrays.asList(
            "Milk", "Eggs", "Peanuts", "Tree nuts", "Soy", "Wheat", "Sesame", "Fish", "Shellfish",
            "Mustard");

    private static String dictionary(String allergens) {
        return "{\"schemaVersion\": 1, \"allergens\": " + allergens + "}";
    }

    private static void rejects(String json, String because) {
        try {
            TestDictionaries.of(json);
            fail("expected a MalformedDictionaryException: " + because);
        } catch (AllergenDictionary.MalformedDictionaryException expected) {
            // The point of the test.
        }
    }

    // ---- the shipped file --------------------------------------------------------------------

    @Test
    public void shippedDictionaryLoadsInOrder() {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Allergen allergen : DICT.allergens()) {
            ids.add(allergen.id());
            labels.add(allergen.label());
        }
        assertEquals(EXPECTED_IDS, ids);
        assertEquals(EXPECTED_LABELS, labels);
    }

    @Test
    public void shippedOrderMatchesPosition() {
        for (int i = 0; i < DICT.allergens().size(); i++) {
            assertEquals(i, DICT.allergens().get(i).order());
        }
    }

    @Test
    public void everyShippedAllergenHasTerms() {
        for (Allergen allergen : DICT.allergens()) {
            assertFalse(allergen.id(), DICT.terms(allergen).isEmpty());
        }
    }

    /** Phrases are matched after normalisation, so the source must already be in that form. */
    @Test
    public void shippedPhrasesAreLowerCaseAscii() {
        for (Allergen allergen : DICT.allergens()) {
            List<String[]> all = new ArrayList<>();
            all.addAll(DICT.terms(allergen));
            all.addAll(DICT.ambiguousTerms(allergen));
            all.addAll(DICT.exclusions(allergen));
            for (String[] phrase : all) {
                for (String token : phrase) {
                    assertEquals(allergen.id() + ": \"" + token + "\" must be lower case",
                            token.toLowerCase(java.util.Locale.ROOT), token);
                    assertTrue(allergen.id() + ": \"" + token + "\" must be plain ASCII",
                            token.matches("[a-z0-9]+"));
                }
            }
        }
    }

    // ---- lookups ----------------------------------------------------------------------------

    @Test
    public void byLabelIgnoresCaseAndSurroundingSpace() {
        Allergen treeNuts = DICT.byId("TREE_NUTS");
        assertEquals(treeNuts, DICT.byLabel("Tree nuts"));
        assertEquals(treeNuts, DICT.byLabel("tree NUTS"));
        assertEquals(treeNuts, DICT.byLabel("  Tree nuts  "));
    }

    @Test
    public void unknownLookupsAreNullNotAnError() {
        assertNull(DICT.byId("UNICORN"));
        assertNull(DICT.byId(null));
        assertNull(DICT.byLabel("Unicorn"));
        assertNull(DICT.byLabel(null));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void allergensCannotBeModified() {
        DICT.allergens().add(null);
    }

    // ---- Allergen value semantics ------------------------------------------------------------

    @Test
    public void allergensAreEqualByIdAndOrderedByPosition() {
        Allergen milk = DICT.byId("MILK");
        Allergen eggs = DICT.byId("EGGS");

        assertEquals(milk, DICT.byLabel("Milk"));
        assertEquals(milk.hashCode(), DICT.byLabel("Milk").hashCode());
        assertNotEquals(milk, eggs);
        assertTrue(milk.compareTo(eggs) < 0);
        assertEquals("MILK", milk.toString());
    }

    /** An id tie-break keeps compareTo consistent with equals, which a sorted set relies on. */
    @Test
    public void allergensSharingAPositionDoNotCollapse() {
        AllergenDictionary other = TestDictionaries.of(dictionary(
                "[{\"id\": \"FIGS\", \"label\": \"Figs\", \"terms\": [\"fig\"]}]"));
        Allergen milk = DICT.byId("MILK");
        Allergen figs = other.byId("FIGS");

        assertEquals(milk.order(), figs.order());
        assertNotEquals(0, milk.compareTo(figs));
        assertEquals(2, new java.util.TreeSet<>(Arrays.asList(milk, figs)).size());
    }

    // ---- a foreign allergen must fail loudly -------------------------------------------------

    @Test
    public void vocabularyLookupRejectsAForeignAllergen() {
        Allergen foreign = TestDictionaries.of(dictionary(
                "[{\"id\": \"FIGS\", \"label\": \"Figs\", \"terms\": [\"fig\"]}]")).byId("FIGS");
        assertFalse(DICT.contains(foreign));
        try {
            DICT.terms(foreign);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Returning an empty list here would read as "not detected".
        }
    }

    @Test
    public void analyserRejectsAForeignAllergenRatherThanReportingNotDetected() {
        Allergen foreign = TestDictionaries.of(dictionary(
                "[{\"id\": \"FIGS\", \"label\": \"Figs\", \"terms\": [\"fig\"]}]")).byId("FIGS");
        try {
            Analyzer.analyse("Ingredients: sugar, figs, salt", DICT,
                    Collections.singletonList(foreign));
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // The point of the test.
        }
    }

    // ---- schema validation ------------------------------------------------------------------

    @Test
    public void malformedDocumentsAreRejected() {
        rejects("not json at all", "not JSON");
        rejects("[]", "a top-level array");
        rejects("{\"allergens\": []}", "no schemaVersion");
        rejects("{\"schemaVersion\": 2, \"allergens\": []}", "an unknown schemaVersion");
        rejects("{\"schemaVersion\": 1}", "no allergens key");
        rejects(dictionary("{}"), "allergens is not an array");
        rejects(dictionary("[]"), "an empty allergens array");
        rejects(dictionary("[\"MILK\"]"), "an entry that is not an object");
    }

    @Test
    public void malformedEntriesAreRejected() {
        rejects(dictionary("[{\"label\": \"Milk\", \"terms\": [\"milk\"]}]"), "no id");
        rejects(dictionary("[{\"id\": \"milk\", \"label\": \"Milk\", \"terms\": [\"milk\"]}]"),
                "a lower-case id");
        rejects(dictionary("[{\"id\": \"MILK\", \"terms\": [\"milk\"]}]"), "no label");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"  \", \"terms\": [\"milk\"]}]"),
                "a blank label");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\"}]"), "no terms");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": []}]"),
                "an empty terms list");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": \"milk\"}]"),
                "terms that are not an array");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": [\"milk\"],"
                + " \"ambiguous\": \"caramel\"}]"), "ambiguous that is not an array");
    }

    @Test
    public void duplicateIdsAndLabelsAreRejected() {
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": [\"milk\"]},"
                        + " {\"id\": \"MILK\", \"label\": \"Dairy\", \"terms\": [\"dairy\"]}]"),
                "a duplicate id");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": [\"milk\"]},"
                        + " {\"id\": \"DAIRY\", \"label\": \"milk\", \"terms\": [\"dairy\"]}]"),
                "labels differing only in case, which byLabel could not tell apart");
    }

    /** A phrase with no matchable words is a typo that would otherwise never match anything. */
    @Test
    public void phrasesThatTokeniseToNothingAreRejected() {
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": [\"5%\"]}]"),
                "a numeric phrase");
        rejects(dictionary("[{\"id\": \"MILK\", \"label\": \"Milk\", \"terms\": [\"milk\", \"\"]}]"),
                "an empty phrase");
    }

    // ---- order within a bucket is meaningful -------------------------------------------------

    @Test
    public void theFirstMatchingTermIsTheOneReported() {
        String terms = "\"terms\": [\"soy lecithin\", \"lecithin\"]";
        String reversed = "\"terms\": [\"lecithin\", \"soy lecithin\"]";
        String text = "Ingredients: sugar, cocoa butter, soy lecithin, vanilla";

        AllergenDictionary first = TestDictionaries.of(dictionary(
                "[{\"id\": \"SOY\", \"label\": \"Soy\", " + terms + "}]"));
        AllergenDictionary second = TestDictionaries.of(dictionary(
                "[{\"id\": \"SOY\", \"label\": \"Soy\", " + reversed + "}]"));

        assertEquals("soy lecithin", matchedTerm(first, text));
        assertEquals("lecithin", matchedTerm(second, text));
    }

    private static String matchedTerm(AllergenDictionary dictionary, String text) {
        AnalysisReport report = Analyzer.analyse(text, dictionary, dictionary.allergens());
        return report.results().get(0).findings().get(0).matchedTerm();
    }
}
