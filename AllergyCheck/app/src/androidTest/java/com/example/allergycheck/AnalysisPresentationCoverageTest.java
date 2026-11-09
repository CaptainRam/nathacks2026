package com.example.allergycheck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.analysis.Finding;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** W-01–W-05, W-09, W-10 and W-12 against packaged strings and the actual result view. */
@RunWith(AndroidJUnit4.class)
public class AnalysisPresentationCoverageTest {
    private static final String MAY_CONTAIN_RED_WARNING = "may_contain_red_warning";

    private Context context;
    private AllergenDictionary dictionary;
    private Boolean originalMayContainRedWarning;

    @Before
    public void loadResources() {
        context = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_AllergyCheck);
        dictionary = AllergenDictionaryProvider.get(context);
        originalMayContainRedWarning = AllergenPreferences.preferences(context)
                .contains(MAY_CONTAIN_RED_WARNING)
                ? AllergenPreferences.mayContainRedWarning(context) : null;
        AllergenPreferences.preferences(context).edit().remove(MAY_CONTAIN_RED_WARNING).apply();
    }

    @After
    public void restorePreferences() {
        if (originalMayContainRedWarning == null) {
            AllergenPreferences.preferences(context).edit().remove(MAY_CONTAIN_RED_WARNING).apply();
        } else {
            AllergenPreferences.saveMayContainRedWarning(context, originalMayContainRedWarning);
        }
    }

    @Test
    public void notDetectedExplainsThatOnlyTheReadTextWasChecked() {
        AnalysisReport report = analyse("Ingredients: sugar, water, salt.", "MILK");
        assertEquals(AnalysisReport.Status.NOT_DETECTED, report.overall());
        render(report, root -> {
            String explanation = text(root, R.id.verdict_message);
            assertTrue(explanation.contains("in the text that was read"));
            assertTrue(explanation.contains("not a guarantee"));
        });
    }

    @Test
    public void unreadableIsNeutralAndHidesIngredientsAndConfidenceNote() {
        AnalysisReport report = analyse("", "MILK");
        assertEquals(AnalysisReport.Status.UNREADABLE, report.overall());
        render(report, root -> {
            assertEquals(MaterialColors.getColor(root,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh), color(root));
            assertIcon(root, R.drawable.ic_info);
            assertTrue(text(root, R.id.verdict_message).contains("NOT a clear result"));
            assertEquals(View.GONE, root.findViewById(R.id.ingredients_card).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.ingredients_section_title).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.low_confidence_note).getVisibility());
        });
    }

    @Test
    public void definiteFindingsUseTheErrorTone() {
        AnalysisReport report = analyse("Ingredients: milk, sugar, salt.", "MILK");
        assertEquals(AnalysisReport.Status.CONTAINS, report.overall());
        render(report, root -> assertEquals(MaterialColors.getColor(root,
                com.google.android.material.R.attr.colorErrorContainer), color(root)));
    }

    @Test
    public void precautionaryFindingsUseTheErrorToneByDefault() {
        assertTrue(AllergenPreferences.mayContainRedWarning(context));
        AnalysisReport report = analyse("Ingredients: sugar, water, salt. May contain soy.", "SOY");
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.overall());
        render(report, root -> {
            assertResultTone(root, MaterialColors.getColor(root,
                    com.google.android.material.R.attr.colorErrorContainer),
                    MaterialColors.getColor(root,
                            com.google.android.material.R.attr.colorOnErrorContainer));
            assertPrecautionaryText(root, report);
        });
    }

    @Test
    public void precautionaryToneCanBeChangedWithoutChangingTheFinding() {
        AnalysisReport report = analyse("Ingredients: sugar, water, salt. May contain soy.", "SOY");
        AllergenPreferences.saveMayContainRedWarning(context, false);
        render(report, root -> {
            AnalysisResultView resultView = new AnalysisResultView(root);
            resultView.showReport(report, false);
            assertResultTone(root, ContextCompat.getColor(context, R.color.status_warning_container),
                    ContextCompat.getColor(context, R.color.status_on_warning_container));
            assertPrecautionaryText(root, report);

            AllergenPreferences.saveMayContainRedWarning(context, true);
            resultView.showReport(report, false);
            assertResultTone(root, MaterialColors.getColor(root,
                    com.google.android.material.R.attr.colorErrorContainer),
                    MaterialColors.getColor(root,
                            com.google.android.material.R.attr.colorOnErrorContainer));
            assertPrecautionaryText(root, report);
        });
    }

    @Test
    public void possibleFindingsKeepTheWarningToneForEitherPreference() {
        AnalysisReport report = analyse("Ingredients: sugar, lecithin, salt.", "SOY");
        assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
        for (boolean redWarning : new boolean[] {true, false}) {
            AllergenPreferences.saveMayContainRedWarning(context, redWarning);
            render(report, root -> assertResultTone(root,
                    ContextCompat.getColor(context, R.color.status_warning_container),
                    ContextCompat.getColor(context, R.color.status_on_warning_container)));
        }
    }

    @Test
    public void evidenceDistinguishesIngredientContainsAndPrecautionarySources() {
        AnalysisReport report = analyse("Ingredients: milk, sugar, salt. "
                + "Contains: soy. May contain peanuts.", "MILK", "SOY", "PEANUTS");
        AnalysisMessage message = new AnalysisMessage(context.getResources());
        for (AllergenResult result : report.results()) {
            assertFalse(result.findings().isEmpty());
            Finding finding = result.findings().get(0);
            int template;
            switch (result.allergen().id()) {
                case "SOY":
                    assertEquals(Finding.Source.CONTAINS_STATEMENT, finding.source());
                    template = R.string.evidence_contains_statement;
                    break;
                case "PEANUTS":
                    assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT, finding.source());
                    template = R.string.evidence_may_contain_statement;
                    break;
                default:
                    assertEquals("MILK", result.allergen().id());
                    assertEquals(Finding.Source.INGREDIENT, finding.source());
                    template = R.string.evidence_ingredient;
            }
            assertEquals("• " + context.getString(template, finding.evidence(), finding.matchedTerm()),
                    message.evidence(result));
        }
        assertEquals(3, report.results().size());
    }

    @Test
    public void possibleEvidenceExplainsAmbiguityAndOcrUncertainty() {
        AnalysisMessage message = new AnalysisMessage(context.getResources());
        for (String[] example : new String[][] {
                {"lecithin", "SOY", context.getString(R.string.reason_ambiguous)},
                {"peanu1", "PEANUTS", context.getString(R.string.reason_uncertain_read)}}) {
            AnalysisReport report = analyse("Ingredients: sugar, " + example[0] + ", salt.", example[1]);
            assertEquals(AnalysisReport.Status.POSSIBLE, report.overall());
            String evidence = message.evidence(report.results().get(0));
            assertTrue(evidence.contains(example[0]));
            assertTrue(evidence.contains(example[2]));
        }
    }

    @Test
    public void nestedIngredientsAreIndentedAndCountOnlyTheirParents() {
        AnalysisReport report = analyse("Ingredients: chocolate (sugar, milk fat), salt.", "MILK");
        render(report, root -> {
            assertEquals(context.getString(R.string.section_ingredients, 2),
                    text(root, R.id.ingredients_section_title));
            assertTrue(text(root, R.id.ingredients_text).contains("\n    • milk fat"));
            assertEquals(View.VISIBLE, root.findViewById(R.id.ingredients_card).getVisibility());
        });
    }

    @Test
    public void lowConfidenceNoteAppearsForACroppedReadablePanel() {
        AnalysisReport report = analyse("milk powder, salt", "MILK");
        assertTrue(report.lowConfidence());
        render(report, root -> {
            assertEquals(View.VISIBLE, root.findViewById(R.id.low_confidence_note).getVisibility());
            assertEquals(context.getString(R.string.low_confidence_warning),
                    text(root, R.id.low_confidence_note));
        });
    }

    @Test
    public void replacingAReportWithAFailureClearsOldEvidenceButKeepsCautions() {
        render(analyse("Ingredients: milk, sugar, salt.", "MILK"), root -> {
            new AnalysisResultView(root).showMessage("Read failed", "Try again");
            assertEquals("Read failed", text(root, R.id.verdict_title));
            assertEquals("Try again", text(root, R.id.verdict_message));
            assertEquals(MaterialColors.getColor(root,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh), color(root));
            assertEquals(0, ((ViewGroup) root.findViewById(R.id.allergen_rows)).getChildCount());
            assertEquals(View.GONE, root.findViewById(R.id.ingredients_card).getVisibility());
            assertCautions(root);
        });
    }

    @Test
    public void everyRuleBasedVerdictRetainsTheDisclaimerAndCaution() {
        for (String label : new String[] {"", "Ingredients: milk, sugar, salt.",
                "Ingredients: sugar, water, salt.", "Ingredients: sugar, lecithin, salt.",
                "Ingredients: sugar, salt, water. May contain soy."}) {
            render(analyse(label, "MILK", "SOY"), this::assertCautions);
        }
        render(analyse("Ingredients: milk, sugar, salt."), this::assertCautions);
    }

    private void assertCautions(ViewGroup root) {
        assertEquals(View.VISIBLE, root.findViewById(R.id.result_caution).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.result_disclaimer).getVisibility());
        assertEquals(context.getString(R.string.result_caution), text(root, R.id.result_caution));
        assertEquals(context.getString(R.string.disclaimer), text(root, R.id.result_disclaimer));
    }

    private void assertPrecautionaryText(ViewGroup root, AnalysisReport report) {
        assertEquals(AnalysisReport.Status.MAY_CONTAIN, report.overall());
        assertEquals(context.getString(R.string.status_may_contain_title),
                text(root, R.id.verdict_title));
        assertEquals(context.getString(R.string.result_may_contain), text(root, R.id.allergen_status));
        AllergenResult result = report.results().get(0);
        assertEquals(Finding.Source.MAY_CONTAIN_STATEMENT, result.findings().get(0).source());
        assertEquals(new AnalysisMessage(context.getResources()).evidence(result),
                text(root, R.id.allergen_evidence));
    }

    private static void assertResultTone(ViewGroup root, int background, int foreground) {
        assertEquals(background, color(root));
        assertEquals(foreground, ((TextView) root.findViewById(R.id.verdict_title)).getCurrentTextColor());
        assertEquals(foreground, ((TextView) root.findViewById(R.id.verdict_message)).getCurrentTextColor());
        TextView badge = root.findViewById(R.id.allergen_status);
        assertEquals(background, badge.getBackgroundTintList().getDefaultColor());
        assertEquals(foreground, badge.getCurrentTextColor());
    }

    private AnalysisReport analyse(String label, String... ids) {
        return Analyzer.analyse(label, dictionary, Arrays.stream(ids)
                .map(dictionary::byId).collect(Collectors.toList()));
    }

    private void render(AnalysisReport report, Consumer<ViewGroup> assertion) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            FrameLayout parent = new FrameLayout(context);
            ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.view_analysis_result, parent, false);
            root.setVisibility(View.VISIBLE);
            new AnalysisResultView(root).showReport(report, false);
            assertion.accept(root);
        });
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }

    private static int color(View root) {
        return ((MaterialCardView) root.findViewById(R.id.verdict_card))
                .getCardBackgroundColor().getDefaultColor();
    }

    private void assertIcon(View root, int expectedResource) {
        ImageView icon = root.findViewById(R.id.verdict_icon);
        Drawable actual = icon.getDrawable();
        Drawable expected = AppCompatResources.getDrawable(context, expectedResource).mutate();
        expected.setTintList(icon.getImageTintList());
        expected.setState(actual.getState());
        Bitmap actualPixels = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Bitmap expectedPixels = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Rect previousBounds = new Rect(actual.getBounds());
        try {
            actual.setBounds(0, 0, 48, 48);
            expected.setBounds(0, 0, 48, 48);
            actual.draw(new Canvas(actualPixels));
            expected.draw(new Canvas(expectedPixels));
            assertTrue("unreadable verdict must use the information icon", actualPixels.sameAs(expectedPixels));
        } finally {
            actual.setBounds(previousBounds);
            actualPixels.recycle();
            expectedPixels.recycle();
        }
    }
}
