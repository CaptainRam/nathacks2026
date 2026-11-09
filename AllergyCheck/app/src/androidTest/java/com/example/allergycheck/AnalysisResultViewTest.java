package com.example.allergycheck;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.ai.AiDecision;
import com.example.allergycheck.crosscheck.FoodTypeCrossCheck;
import com.example.allergycheck.crosscheck.FoodTypeEstimate;
import com.google.android.material.card.MaterialCardView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.IOException;

/** Which allergen rows the result lists, with and without "show allergens not detected". */
@RunWith(AndroidJUnit4.class)
public class AnalysisResultViewTest {
    private static final String LABEL = "Ingredients: wheat flour, milk, sugar, salt.";

    private final Context context = new ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            R.style.Theme_AllergyCheck);
    private AllergenDictionary dictionary;
    private Allergen milk;
    private Allergen eggs;

    @Before
    public void loadDictionary() {
        dictionary = AllergenDictionaryProvider.get(context);
        milk = dictionary.byId("MILK");
        eggs = dictionary.byId("EGGS");
        assertNotNull(milk);
        assertNotNull(eggs);
    }

    @Test
    public void hidesNotDetectedAllergensByDefault() {
        AnalysisReport report = Analyzer.analyse(LABEL, dictionary, Arrays.asList(milk, eggs));
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());

        ViewGroup root = render(report, false);

        assertEquals(Arrays.asList(milk.label()), rowNames(root));
        assertEquals(View.VISIBLE, root.findViewById(R.id.allergen_section_title).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.allergen_rows).getVisibility());
    }

    @Test
    public void listsNotDetectedAllergensWhenEnabled() {
        AnalysisReport report = Analyzer.analyse(LABEL, dictionary, Arrays.asList(milk, eggs));

        ViewGroup root = render(report, true);

        List<String> names = rowNames(root);
        assertEquals(2, names.size());
        assertEquals(true, names.contains(milk.label()));
        assertEquals(true, names.contains(eggs.label()));
    }

    @Test
    public void hidesAllergenSectionWhenNothingIsLeftToList() {
        AnalysisReport report = Analyzer.analyse(LABEL, dictionary, Arrays.asList(eggs));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());

        ViewGroup root = render(report, false);

        assertEquals(0, rowNames(root).size());
        assertEquals(View.GONE, root.findViewById(R.id.allergen_section_title).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.allergen_rows).getVisibility());
        // The verdict still reports the clear result for every selected allergen.
        assertEquals(context.getString(R.string.status_not_detected_title),
                ((TextView) root.findViewById(R.id.verdict_title)).getText().toString());
    }

    @Test
    public void notDetectedAllergenIsListedWhenEnabledEvenIfItIsTheOnlyOne() {
        AnalysisReport report = Analyzer.analyse(LABEL, dictionary, Arrays.asList(eggs));

        ViewGroup root = render(report, true);

        assertEquals(Arrays.asList(eggs.label()), rowNames(root));
        assertEquals(View.VISIBLE, root.findViewById(R.id.allergen_section_title).getVisibility());
    }

    @Test
    public void failedAiKeepsUncheckedIngredientsVisibleAndShowsIncompleteMessage() throws Exception {
        AnalysisReport matching = Analyzer.analyse("Ingredients: mystery protein, sugar, salt.",
                dictionary, Arrays.asList(milk));
        AnalysisReport report = AiAnalyzer.analyse(matching, (ingredient, allergen) -> {
            throw new IOException("Offline");
        });

        boolean debugLabel = AllergenPreferences.debugLabelText(context);
        AllergenPreferences.saveDebugLabelText(context, true);
        ViewGroup root;
        try {
            root = render(report, false);
        } finally {
            AllergenPreferences.saveDebugLabelText(context, debugLabel);
        }

        assertEquals(Arrays.asList(milk.label()), rowNames(root));
        assertEquals(context.getString(R.string.status_ai_incomplete_title),
                ((TextView) root.findViewById(R.id.verdict_title)).getText().toString());
        assertEquals(View.VISIBLE, root.findViewById(R.id.low_confidence_note).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.ingredients_card).getVisibility());
        String evidence = ((TextView) root.findViewById(R.id.allergen_evidence)).getText().toString();
        assertTrue(evidence.contains("mystery protein"));
        assertTrue(evidence.contains("review manually"));
    }

    @Test
    public void failedAiRetainsDeterministicEvidenceAndIncompleteNote() throws Exception {
        AnalysisReport matching = Analyzer.analyse(LABEL, dictionary, Arrays.asList(milk, eggs));
        AnalysisReport report = AiAnalyzer.analyse(matching, (ingredient, allergen) -> {
            throw new IOException("Offline");
        });

        ViewGroup root = render(report, false);

        assertEquals(context.getString(R.string.status_contains_title),
                ((TextView) root.findViewById(R.id.verdict_title)).getText().toString());
        assertEquals(View.VISIBLE, root.findViewById(R.id.low_confidence_note).getVisibility());
        assertTrue(((TextView) root.findViewById(R.id.low_confidence_note)).getText().toString()
                .contains("could not check every ingredient"));
        assertTrue(((TextView) root.findViewById(R.id.allergen_evidence)).getText().toString()
                .contains("matches milk"));
    }

    @Test
    public void aiRiskShowsOriginalIngredientAndModelScoreWhenNotDetectedRowsAreHidden() throws Exception {
        AnalysisReport matching = Analyzer.analyse("Ingredients: mystery protein, sugar, salt.",
                dictionary, Arrays.asList(milk));
        AnalysisReport report = AiAnalyzer.analyse(matching, (ingredient, allergen) ->
                ingredient.equals("mystery protein") ? AiDecision.scored(0.87d, 0.5d)
                        : AiDecision.of(AiDecision.Verdict.NO_EVIDENCE));

        ViewGroup root = render(report, false);

        assertEquals(Arrays.asList(milk.label()), rowNames(root));
        String evidence = ((TextView) root.findViewById(R.id.allergen_evidence)).getText().toString();
        assertTrue(evidence.contains("mystery protein"));
        assertTrue(evidence.contains("possible source"));
        assertTrue(evidence.contains("87%"));
        assertEquals(context.getString(R.string.result_possible),
                ((TextView) root.findViewById(R.id.allergen_status)).getText().toString());
        assertEquals(context.getString(R.string.ai_analysis_note),
                ((TextView) root.findViewById(R.id.low_confidence_note)).getText().toString());
    }

    @Test
    public void photoMismatchShowsWarningAndHiddenAllergenWithoutChangingLabelReport() throws Exception {
        AnalysisReport report = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singletonList(milk));
        byte[] original = serialized(report);
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Collections.singletonList(milk)));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup root = inflateResult();
            AnalysisResultView view = new AnalysisResultView(root);
            view.showReport(report, false);
            assertEquals(0, rowNames(root).size());

            view.showFoodTypeCrossCheck(advice);

            assertEquals(context.getString(R.string.crosscheck_review_title),
                    text(root, R.id.verdict_title));
            assertTrue(text(root, R.id.verdict_message).contains("cookies"));
            assertTrue(text(root, R.id.verdict_message).contains(milk.label()));
            assertEquals(Collections.singletonList(milk.label()), rowNames(root));
            View row = rowFor(root, milk);
            assertEquals(context.getString(R.string.crosscheck_review_status),
                    text(row, R.id.allergen_status));
            assertEquals(context.getColor(R.color.status_warning_container),
                    ((MaterialCardView) root.findViewById(R.id.verdict_card))
                            .getCardBackgroundColor().getDefaultColor());
            TextView status = row.findViewById(R.id.allergen_status);
            assertEquals(context.getColor(R.color.status_warning_container),
                    status.getBackgroundTintList().getDefaultColor());
            String evidence = text(row, R.id.allergen_evidence);
            assertTrue(evidence.contains("Ingredient label: Not detected."));
            assertTrue(evidence.contains("cookies"));
            assertTrue(evidence.contains("unconfirmed"));
            assertEquals(View.GONE, row.findViewById(R.id.allergen_evidence).getVisibility());
            assertTrue(row.performClick());
            assertEquals(View.VISIBLE, row.findViewById(R.id.allergen_evidence).getVisibility());
        });

        assertArrayEquals("Photo advice must not create ingredient findings", original, serialized(report));
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.results().get(0).status());
        assertTrue(report.results().get(0).findings().isEmpty());
    }

    @Test
    public void photoMismatchPreservesContainsVerdictAndExistingLabelEvidence() {
        AnalysisReport report = Analyzer.analyse(LABEL, dictionary, Arrays.asList(milk, eggs));
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Arrays.asList(milk, eggs)));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup root = inflateResult();
            AnalysisResultView view = new AnalysisResultView(root);
            view.showReport(report, false);
            View milkRow = rowFor(root, milk);
            String milkEvidence = text(milkRow, R.id.allergen_evidence);
            String labelSummary = text(root, R.id.verdict_message);

            view.showFoodTypeCrossCheck(advice);

            assertEquals(context.getString(R.string.status_contains_title),
                    text(root, R.id.verdict_title));
            String summary = text(root, R.id.verdict_message);
            assertTrue(summary.contains(labelSummary));
            assertTrue(summary.contains("cookies"));
            assertTrue(summary.contains(eggs.label()));
            assertSame(milkRow, rowFor(root, milk));
            assertEquals(milkEvidence, text(milkRow, R.id.allergen_evidence));
            assertEquals(context.getString(R.string.result_contains),
                    text(milkRow, R.id.allergen_status));
            assertEquals(context.getString(R.string.crosscheck_review_status),
                    text(rowFor(root, eggs), R.id.allergen_status));
        });
    }

    @Test
    public void displayRefreshKeepsMismatchRowAndExpandedExplanation() {
        AnalysisReport report = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Arrays.asList(milk, eggs));
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Collections.singletonList(milk)));
        boolean previous = AllergenPreferences.showNotDetected(context);
        AllergenPreferences.saveShowNotDetected(context, false);
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ViewGroup root = inflateResult();
                AnalysisResultView view = new AnalysisResultView(root);
                view.showReport(report, false);
                view.showFoodTypeCrossCheck(advice);
                View row = rowFor(root, milk);
                row.performClick();
                String summary = text(root, R.id.verdict_message);

                view.refreshDisplayPreferences();

                assertEquals(Collections.singletonList(milk.label()), rowNames(root));
                assertSame(row, rowFor(root, milk));
                assertEquals(View.VISIBLE, row.findViewById(R.id.allergen_evidence).getVisibility());
                assertEquals(summary, text(root, R.id.verdict_message));
                assertEquals(context.getString(R.string.crosscheck_review_status),
                        text(row, R.id.allergen_status));
            });
        } finally {
            AllergenPreferences.saveShowNotDetected(context, previous);
        }
    }

    @Test
    public void clearingAdviceOrFindingNoMismatchRestoresLabelPresentation() {
        AnalysisReport report = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singletonList(milk));
        FoodTypeCrossCheck mismatch = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cookies", Collections.singletonList(milk)));
        FoodTypeCrossCheck noMismatch = FoodTypeCrossCheck.compare(report,
                new FoodTypeEstimate("cocoa", Collections.emptyList()));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup root = inflateResult();
            AnalysisResultView view = new AnalysisResultView(root);
            view.showReport(report, false);
            String title = text(root, R.id.verdict_title);
            String summary = text(root, R.id.verdict_message);

            view.showFoodTypeCrossCheck(mismatch);
            view.showFoodTypeCrossCheck(null);
            assertEquals(title, text(root, R.id.verdict_title));
            assertEquals(summary, text(root, R.id.verdict_message));
            assertEquals(0, rowNames(root).size());

            view.showFoodTypeCrossCheck(mismatch);
            view.showFoodTypeCrossCheck(noMismatch);
            assertEquals(title, text(root, R.id.verdict_title));
            assertEquals(summary, text(root, R.id.verdict_message));
            assertEquals(0, rowNames(root).size());

            view.showReport(report, true);
            view.showFoodTypeCrossCheck(mismatch);
            view.showFoodTypeCrossCheck(null);
            View row = rowFor(root, milk);
            assertEquals(context.getString(R.string.result_not_detected),
                    text(row, R.id.allergen_status));
            assertEquals("", text(row, R.id.allergen_evidence));
            assertFalse(row.isClickable());
        });
    }

    @Test
    public void newReportAndMessageDiscardPreviousPhotoAdvice() {
        AnalysisReport clear = Analyzer.analyse("Ingredients: sugar, salt, cocoa.", dictionary,
                Collections.singletonList(milk));
        AnalysisReport contains = Analyzer.analyse(LABEL, dictionary, Collections.singletonList(milk));
        FoodTypeCrossCheck advice = FoodTypeCrossCheck.compare(clear,
                new FoodTypeEstimate("cookies", Collections.singletonList(milk)));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup root = inflateResult();
            AnalysisResultView view = new AnalysisResultView(root);
            view.showReport(clear, false);
            view.showFoodTypeCrossCheck(advice);

            view.showReport(contains, false);
            assertEquals(context.getString(R.string.status_contains_title),
                    text(root, R.id.verdict_title));
            assertFalse(text(root, R.id.verdict_message).contains("cookies"));
            assertEquals(context.getString(R.string.result_contains),
                    text(rowFor(root, milk), R.id.allergen_status));

            view.showReport(clear, false);
            view.showFoodTypeCrossCheck(advice);
            view.showMessage("No label", "Choose another ingredient label.");
            assertEquals("No label", text(root, R.id.verdict_title));
            assertEquals("Choose another ingredient label.", text(root, R.id.verdict_message));
            assertEquals(0, rowNames(root).size());

            view.showReport(clear, false);
            assertEquals(context.getString(R.string.status_not_detected_title),
                    text(root, R.id.verdict_title));
            assertFalse(text(root, R.id.verdict_message).contains("cookies"));
            assertEquals(0, rowNames(root).size());
        });
    }

    private ViewGroup inflateResult() {
        return (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.view_analysis, new FrameLayout(context), false);
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }

    private static View rowFor(ViewGroup root, Allergen allergen) {
        ViewGroup rows = root.findViewById(R.id.allergen_rows);
        for (int index = 0; index < rows.getChildCount(); index++) {
            View row = rows.getChildAt(index);
            if (text(row, R.id.allergen_name).equals(allergen.label())) return row;
        }
        throw new AssertionError("Missing allergen row: " + allergen.label());
    }

    private static byte[] serialized(AnalysisReport report) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(report);
        }
        return bytes.toByteArray();
    }

    private ViewGroup render(AnalysisReport report, boolean showNotDetected) {
        ViewGroup[] holder = new ViewGroup[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout parent = new FrameLayout(context);
            ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.view_analysis, parent, false);
            new AnalysisResultView(root).showReport(report, showNotDetected);
            holder[0] = root;
        });
        return holder[0];
    }

    private static List<String> rowNames(ViewGroup root) {
        ViewGroup rows = root.findViewById(R.id.allergen_rows);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            names.add(((TextView) rows.getChildAt(i).findViewById(R.id.allergen_name))
                    .getText().toString());
        }
        return names;
    }
}
