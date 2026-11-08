package com.example.allergycheck.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.allergycheck.ingredients.IngredientExtractor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Missing S-series cases from the analysis branch's test plan, checked against current main. */
public class LabelParserCoverageTest {

    private static List<String> ingredientNames(ParsedLabel label) {
        List<String> names = new ArrayList<>();
        for (IngredientItem ingredient : label.ingredients()) {
            names.add(ingredient.original());
        }
        return names;
    }

    private static void assertStatement(ParsedLabel label, ParsedLabel.Kind kind, String text) {
        assertEquals(1, label.statements().size());
        assertEquals(kind, label.statements().get(0).kind());
        assertEquals(text, label.statements().get(0).text());
    }

    @Test
    public void s11AllergyAdvicePrefixIsRemovedFromContainsStatement() {
        ParsedLabel label = LabelParser.parse(
                "Ingredients: oats, sugar, salt. Allergy advice: contains milk.");
        assertEquals(Arrays.asList("oats", "sugar", "salt"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.CONTAINS, "contains milk");
    }

    @Test
    public void s13ManufacturingPlantWarningStaysSeparateFromIngredients() {
        ParsedLabel label = LabelParser.parse(
                "Ingredients: oats, sugar Manufactured in a plant that also handles sesame.");
        assertEquals(Arrays.asList("oats", "sugar"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN,
                "Manufactured in a plant that also handles sesame");
    }

    @Test
    public void s14CouldContainAtSentenceStartIsPrecautionary() {
        ParsedLabel label = LabelParser.parse("Ingredients: oats, salt. Could contain milk.");
        assertEquals(Arrays.asList("oats", "salt"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN, "Could contain milk");
    }

    @Test
    public void s15FacilityWarningBeforeHeadingIsKept() {
        ParsedLabel label = LabelParser.parse(
                "Packaged in a facility that processes peanuts.\nIngredients: oats, sugar, salt.");
        assertEquals(Arrays.asList("oats", "sugar", "salt"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN,
                "Packaged in a facility that processes peanuts");
    }

    @Test
    public void s16DairyFreeClaimDoesNotBecomeAnIngredientOrStatementBeforeHeading() {
        ParsedLabel label = LabelParser.parse(
                "Dairy-free. Contains: soy.\nIngredients: oats, sugar, salt.");
        assertEquals(Arrays.asList("oats", "sugar", "salt"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.CONTAINS, "Contains: soy");
    }

    @Test
    public void s17NullExtractionStillPreservesStatementsFromTheWholeLabel() {
        String raw = "Contains: milk. Ingredients: oats, salt. May contain peanuts.";
        ParsedLabel label = LabelParser.fromIngredients(raw, null);
        assertEquals(raw, label.rawText());
        assertTrue(label.ingredientHeadingFound());
        assertEquals(Arrays.asList("oats", "salt"), ingredientNames(label));
        assertEquals(2, label.statements().size());
        assertEquals(ParsedLabel.Kind.CONTAINS, label.statements().get(0).kind());
        assertEquals("Contains: milk", label.statements().get(0).text());
        assertEquals(ParsedLabel.Kind.MAY_CONTAIN, label.statements().get(1).kind());
        assertEquals("May contain peanuts", label.statements().get(1).text());
    }

    @Test
    public void s05QuantityPrefixDoesNotCreateContainsStatement() {
        String raw = "Ingredients: Contains less than 2% of: salt, whey.";
        ParsedLabel label = LabelParser.fromIngredients(raw, IngredientExtractor.extract(raw));
        assertEquals(Arrays.asList("salt", "whey"), ingredientNames(label));
        assertTrue(label.statements().isEmpty());
    }

    @Test
    public void s10UnclosedBracketDoesNotHidePrecautionaryStatement() {
        String raw = "Ingredients: sugar, salt (iodized, cocoa. May contain peanuts.";
        ParsedLabel label = LabelParser.fromIngredients(raw, IngredientExtractor.extract(raw));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN, "May contain peanuts");
    }

    @Test
    public void s12SharedEquipmentWarningIsPrecautionary() {
        ParsedLabel label = LabelParser.parse(
                "Ingredients: oats. Made on shared equipment with peanuts.");
        assertEquals(Arrays.asList("oats"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN,
                "Made on shared equipment with peanuts");
    }

    @Test
    public void s14MayAlsoContainWarningIsPrecautionary() {
        ParsedLabel label = LabelParser.parse("Ingredients: oats, salt. May also contain: milk.");
        assertEquals(Arrays.asList("oats", "salt"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN, "May also contain: milk");
    }

    @Test
    public void s14InlineCouldContainWarningIsSplitOff() {
        ParsedLabel label = LabelParser.parse("Ingredients: oats, salt Could contain milk");
        assertEquals(Arrays.asList("oats", "salt"), ingredientNames(label));
        assertStatement(label, ParsedLabel.Kind.MAY_CONTAIN, "Could contain milk");
    }

    @Test
    public void repairedOcrListsKeepAllergenEvidenceThroughBothAnalysisPaths() {
        String[] labels = {
                "Ingredients: sugar, palm oil. whey powder, salt",
                "Ingredients: sugar, vit. C, whey powder",
                "Made with simple ingredients you can trust. INGREDIENTS oats, whey powder, salt",
                "INGREDIENTS. oats, whey powder",
                "lngredients: oats, whey powder",
                "INGREDlENTS: oats, whey powder",
                "Ingredients: Contains less than 2% of: salt, whey",
                "Ingredients: sugar, MILK-\nFAT, salt",
                "Ingredients: sugar, MILK-\nCHOCOLATE, salt"
        };
        for (String label : labels) {
            assertBothPaths(label, "MILK", AnalysisReport.Status.CONTAINS, Finding.Source.INGREDIENT);
        }
        assertBothPaths("INGREDIENTS: SUGAR, PEA-\nNUT OIL", "PEANUTS",
                AnalysisReport.Status.CONTAINS, Finding.Source.INGREDIENT);
        assertBothPaths("Ingredients: sugar, TREE-\nNUT, salt", "TREE_NUTS",
                AnalysisReport.Status.CONTAINS, Finding.Source.INGREDIENT);
    }

    @Test
    public void repairedWarningBoundariesKeepPrecautionaryEvidenceOutOfIngredients() {
        String[] labels = {
                "Ingredients: sugar, salt (iodized, cocoa. May contain peanuts.",
                "Ingredients: sugar, salt Made on shared equipment with peanuts.",
                "Ingredients: sugar, salt May also contain: peanuts.",
                "Ingredients: sugar, salt Could contain peanuts.",
                "Ingredients: sugar, salt, traces of peanuts"
        };
        for (String label : labels) {
            assertBothPaths(label, "PEANUTS", AnalysisReport.Status.MAY_CONTAIN,
                    Finding.Source.MAY_CONTAIN_STATEMENT);
        }
    }

    @Test
    public void rawAndExtractedParsersUseTheSameFirstListAndStorageBoundaries() {
        for (String boundary : new String[] {"Nutrition Facts Calories 120", "Keep in a cool place",
                "Ingredients: cocoa, milk"}) {
            String raw = "Ingredients: sugar, salt. " + boundary + ". Enjoy with milk.";
            assertEquals(Arrays.asList("sugar", "salt"), ingredientNames(LabelParser.parse(raw)));
            assertEquals(IngredientExtractor.extract(raw), ingredientNames(LabelParser.parse(raw)));
            assertBothPaths(raw, "MILK", AnalysisReport.Status.NOT_DETECTED, null);
        }
    }

    @Test
    public void wrappedFreeClaimsStillExcludeAllergens() {
        assertBothPaths("Ingredients: sugar, gluten-\nfree, salt", "WHEAT",
                AnalysisReport.Status.NOT_DETECTED, null);
        assertBothPaths("Ingredients: sugar, milk-\nfree powder, salt", "MILK",
                AnalysisReport.Status.NOT_DETECTED, null);
        assertBothPaths("Ingredients: sugar, PEA-\nNUT-\nFREE, salt", "PEANUTS",
                AnalysisReport.Status.NOT_DETECTED, null);
    }

    @Test
    public void everyShippedSingleWordAllergenTermSurvivesMultipleWraps() {
        AllergenDictionary dictionary = TestDictionaries.shipped();
        for (Allergen allergen : dictionary.allergens()) {
            for (String[] term : dictionary.terms(allergen)) {
                if (term.length != 1 || term[0].length() < 3) continue;
                String word = term[0];
                String wrapped = splitAcrossThreeLines(word);
                String raw = "Ingredients: sugar, " + wrapped + ", salt.";
                for (AnalysisReport report : new AnalysisReport[] {
                        Analyzer.analyse(raw, dictionary, Collections.singletonList(allergen)),
                        Analyzer.analyse(IngredientExtractor.extract(raw), raw, dictionary,
                                Collections.singletonList(allergen))}) {
                    assertEquals(word, AnalysisReport.Status.CONTAINS, report.overall());
                    Finding finding = report.results().get(0).findings().get(0);
                    assertEquals(word, Finding.Reason.NONE, finding.reason());
                    assertEquals(word, Finding.Source.INGREDIENT, finding.source());
                    assertEquals(word, word, finding.evidence());
                }
            }
        }
    }

    @Test
    public void everyShippedVocabularyTokenHasAStableSingleWordWrapSpelling() {
        AllergenDictionary dictionary = TestDictionaries.shipped();
        for (Allergen allergen : dictionary.allergens()) {
            List<String[]> phrases = new ArrayList<>(dictionary.terms(allergen));
            phrases.addAll(dictionary.ambiguousTerms(allergen));
            phrases.addAll(dictionary.exclusions(allergen));
            for (String[] phrase : phrases) {
                for (String word : phrase) {
                    if (word.length() < 3 || !word.matches("[a-z]+")) continue;
                    assertEquals(word, word, LabelSyntax.prepare(splitAcrossThreeLines(word)));
                }
            }
        }
    }

    private static String splitAcrossThreeLines(String word) {
        int first = Math.max(1, word.length() / 3);
        int second = Math.max(first + 1, word.length() * 2 / 3);
        return word.substring(0, first) + "-\n" + word.substring(first, second)
                + "-\n" + word.substring(second);
    }

    private static void assertBothPaths(String raw, String allergenId, AnalysisReport.Status status,
                                        Finding.Source source) {
        AllergenDictionary dictionary = TestDictionaries.shipped();
        List<Allergen> selected = Collections.singletonList(dictionary.byId(allergenId));
        for (AnalysisReport report : new AnalysisReport[] {
                Analyzer.analyse(raw, dictionary, selected),
                Analyzer.analyse(IngredientExtractor.extract(raw), raw, dictionary, selected)}) {
            assertEquals(raw, status, report.overall());
            if (source == null) assertTrue(raw, report.results().get(0).findings().isEmpty());
            else {
                assertEquals(raw, source, report.results().get(0).findings().get(0).source());
                assertEquals(raw, Finding.Reason.NONE, report.results().get(0).findings().get(0).reason());
            }
        }
    }
}
