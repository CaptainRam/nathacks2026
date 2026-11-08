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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
