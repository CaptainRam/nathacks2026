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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
