package com.example.allergycheck.ocr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ingredients.IngredientExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Two things are being pinned here, and the false-positive half matters more than the catch half.
 *
 * <p>A missed garbled frame costs one auto-capture that should have waited — the user still gets a
 * report, and the post-capture pipeline reads the panel far better than the live frame did. A false
 * positive, though, holds the shutter on a label that was reading perfectly, and the user has no way
 * to tell why; do that on ordinary labels and auto-capture is simply broken. So every realistic clean
 * label below is as much a part of the contract as the fragmentation cases.
 *
 * <p>Both of the real bugs this check went through were found by exactly these two lists, and both
 * have a test named after them: {@link #chemicalPrefixesAreNotFragments} and
 * {@link #soyLecithinSplitInTwoIsCaught}.
 */
public class ReadQualityTest {

    // ---- clean reads: ordinary labels that must never hold the shutter ------------------------

    @Test
    public void aPlainEnglishListIsClean() {
        assertTrue(looksClean("Water", "Sugar", "Citric Acid", "Natural Flavour",
                "Sodium Benzoate"));
    }

    @Test
    public void aFrenchListIsClean() {
        // Canadian labels are bilingual, so half the panels this ever sees are in French. A French
        // function word is not a misread.
        assertTrue(looksClean("Sucre", "eau", "acide citrique", "arome naturel", "sel", "lait"));
    }

    @Test
    public void shortRealWordsAreNotFragments() {
        // Three letters is the floor on purpose: these are all words, not debris.
        assertTrue(looksClean("sel", "eau", "soy", "dye", "BHT", "oat", "rye", "egg"));
    }

    @Test
    public void vitaminLettersAreNotFragments() {
        assertTrue(looksClean("Milk", "Vitamin D", "Vitamin A Palmitate", "Riboflavin",
                "Niacinamide", "Folic Acid", "salt"));
    }

    @Test
    public void additiveCodesAreNotFragments() {
        // Every one of these carries a digit, which is what separates an additive code from a
        // broken word — and the reason digit substitution is not what this check looks for.
        assertTrue(looksClean("Oats", "Vitamin B12", "E322", "Vitamin D3", "Red 40", "Blue 1",
                "sugar", "salt"));
    }

    @Test
    public void fdAndCColourNamesAreNotFragments() {
        assertTrue(looksClean("Sugar", "FD&C Red No. 40", "Yellow 5", "citric acid", "water",
                "salt"));
    }

    @Test
    public void chemicalPrefixesAreNotFragments() {
        // The first false positive this check shipped with. The tokeniser splits on the hyphen, so
        // each of these opens an item with a bare letter; three of them in one list broke the ratio
        // and held the shutter on a perfectly good multivitamin panel. Licensed by position now —
        // a lone letter that *starts* an item is a prefix, since a fragment sits where a word broke.
        assertTrue(looksClean("Water", "D-alpha tocopheryl acetate", "N-acetyl cysteine",
                "L-ascorbic acid", "DL-alpha tocopherol", "salt", "sugar"));
    }

    @Test
    public void labelShorthandIsNotAFragment() {
        assertTrue(looksClean("pH adjusted water", "sugar", "salt", "citric acid", "flavour"));
    }

    @Test
    public void aShortListWithNoFragmentsIsClean() {
        assertTrue(looksClean("Corn", "salt"));
    }

    @Test
    public void noListAtAllIsClean() {
        // Absence of evidence is not evidence of garbling. Whether a list is present is a separate
        // question that LiveLabelReader.interpret has already answered.
        assertTrue(ReadQuality.looksClean(Collections.<String>emptyList()));
        assertTrue(ReadQuality.looksClean(null));
    }

    // ---- garbled reads: the frames auto-capture must not shoot --------------------------------

    @Test
    public void theCitricAcidCaseIsCaught() {
        // The frame that started all this. It parses — heading plus comma-separated items is all
        // the extractor asks — so the verdict went green and the shutter fired on a lost word.
        assertFalse(looksClean("Water", "Sugar", "Citric Ac d", "Natural Flavour",
                "Sodium Benzoate"));
    }

    @Test
    public void soyLecithinSplitInTwoIsCaught() {
        // The catch the first allowlist gave away: "so" and "y" are both real words in the two
        // official languages, so an allergen term broken in half read as innocent prose and passed.
        // This is the most consequential fragment there is — a misread allergen comes back as a
        // confident "not detected" rather than as a failed scan.
        assertFalse(looksClean("Milk", "so y lecithin", "sugar", "cocoa butter", "salt",
                "vanilla extract"));
    }

    @Test
    public void aSplitAllergenTermIsCaught() {
        assertFalse(looksClean("Enriched wheat flour", "sugar", "pean ut butter", "cocoa", "salt",
                "vanilla"));
    }

    @Test
    public void aWordShatteredIntoLettersIsCaught() {
        assertFalse(looksClean("Water", "su g ar", "citric acid", "natural flavour", "salt",
                "sodium benzoate"));
    }

    @Test
    public void broadFragmentationIsCaught() {
        assertFalse(looksClean("Wheat fl ur", "sugar", "veg etable oil", "co coa", "salt",
                "soy lec thin", "water"));
    }

    @Test
    public void twoFragmentsInARowAreEnoughOnTheirOwn() {
        // The run rule, isolated: two fragments adjacent is one word in pieces, and no amount of
        // surrounding clean text makes that frame worth shooting.
        List<String> items = new ArrayList<>(Arrays.asList("ac d"));
        for (int filler = 0; filler < 30; filler++) {
            items.add("sugar");
        }
        assertFalse(ReadQuality.looksClean(items));
    }

    @Test
    public void aNumberBetweenTwoFragmentsDoesNotBreakTheRun() {
        // "ac 2 d" is still one word in pieces; a quantity sitting in the middle of the wreckage
        // is not evidence that the read recovered.
        assertFalse(looksClean("Water", "citric ac 2 d", "sugar", "salt", "natural flavour"));
    }

    // ---- the ratio rule and its floor --------------------------------------------------------

    @Test
    public void oneIsolatedFragmentInALongListIsTolerated() {
        // Deliberately not zero-tolerance. Legitimate lone letters exist, and a single one in a
        // long list is far likelier to be one of those than a broken word.
        List<String> items = new ArrayList<>(Arrays.asList(
                "water", "sugar", "citric acid", "natural flavour", "sodium benzoate", "cocoa",
                "wheat flour", "vanilla extract"));
        items.add("ac");
        int tokens = wordTokens(items);
        // Computed, not assumed: the list has to be long enough that one fragment sits under the
        // ratio, or this would be asserting the floor rather than the tolerance.
        assertTrue(tokens >= ReadQuality.MIN_TOKENS_FOR_RATIO);
        assertTrue("one fragment in " + tokens + " tokens should be tolerated",
                1 <= tokens * ReadQuality.MAX_FRAGMENT_RATIO);
        assertTrue(ReadQuality.looksClean(items));
    }

    @Test
    public void severalScatteredFragmentsBreakTheRatio() {
        // Non-adjacent, so the run rule cannot be what fails this: it is the sheer density of
        // debris that says the whole read is unreliable.
        List<String> items = Arrays.asList("water", "ac", "sugar", "ut", "citric acid",
                "natural flavour", "salt", "cocoa butter");
        // Asserted rather than assumed: with fewer tokens than the floor the ratio would not be
        // applied at all, and this test would be passing for the wrong reason.
        assertTrue(wordTokens(items) >= ReadQuality.MIN_TOKENS_FOR_RATIO);
        assertFalse(ReadQuality.looksClean(items));
    }

    @Test
    public void theRatioIsNotAppliedToVeryShortLists() {
        // Below the floor a single legitimate fragment would blow the ratio on its own, so only
        // the run rule gets a say. "Corn, ac" is thin evidence either way, and holding the shutter
        // on it would strand a user photographing a two-ingredient package.
        List<String> items = Arrays.asList("Corn", "ac");
        assertTrue(wordTokens(items) < ReadQuality.MIN_TOKENS_FOR_RATIO);
        assertTrue(ReadQuality.looksClean(items));
    }

    // ---- the real input shape ----------------------------------------------------------------

    @Test
    public void judgesWhatTheExtractorActuallyProduces() {
        // The unit tests above hand in item lists directly. This one goes through the extractor, so
        // the contract is pinned against the real shape of the live path's input rather than
        // against an assumption about it.
        String clean = "Ingredients: Enriched wheat flour, sugar, vegetable oil, cocoa, salt, "
                + "soy lecithin, vanilla extract.";
        String garbled = "Ingredients: Enriched wheat flour, sugar, vegetable oil, cocoa, salt, "
                + "so y lecithin, vanilla extract.";

        List<String> cleanItems = IngredientExtractor.extract(clean);
        List<String> garbledItems = IngredientExtractor.extract(garbled);
        // Both parse: that is precisely the problem this check exists for.
        assertFalse("the clean label should parse", cleanItems.isEmpty());
        assertFalse("the garbled label parses too — that is the bug", garbledItems.isEmpty());

        assertTrue(ReadQuality.looksClean(cleanItems));
        assertFalse(ReadQuality.looksClean(garbledItems));
    }

    private static boolean looksClean(String... ingredients) {
        return ReadQuality.looksClean(Arrays.asList(ingredients));
    }

    /** Mirrors the counting rule under test closely enough to assert which rule is in play. */
    private static int wordTokens(List<String> items) {
        int count = 0;
        for (String item : items) {
            for (String token : item.split("[^\\p{L}\\p{N}]+")) {
                if (!token.isEmpty() && token.matches(".*\\p{L}.*")) {
                    count++;
                }
            }
        }
        return count;
    }
}
