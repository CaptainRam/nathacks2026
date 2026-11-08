package com.example.allergycheck;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.IngredientItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link AnalysisReport} as the title and body of the result dialog: the verdict, the
 * per-allergen breakdown with the label text behind each finding, and the ingredients that were
 * read.
 *
 * <p>Wording rules the UI must not blur: "not detected" always says "in the text that was read",
 * and an unreadable label gets its own verdict rather than being shown as a clear result.
 */
final class AnalysisMessage {

    private final Resources resources;

    AnalysisMessage(@NonNull Resources resources) {
        this.resources = resources;
    }

    /** The verdict headline, e.g. "Contains an allergen". */
    @NonNull
    String title(@NonNull AnalysisReport report) {
        switch (report.overall()) {
            case CONTAINS:
                return resources.getString(R.string.status_contains_title);
            case MAY_CONTAIN:
                return resources.getString(R.string.status_may_contain_title);
            case POSSIBLE:
                return resources.getString(R.string.status_possible_title);
            case NOT_DETECTED:
                return resources.getString(R.string.status_not_detected_title);
            case UNREADABLE:
                return resources.getString(R.string.status_unreadable_title);
            default:
                return resources.getString(R.string.status_no_allergens_title);
        }
    }

    /** The full body: verdict explanation, per-allergen findings, ingredients and the disclaimer. */
    @NonNull
    String body(@NonNull AnalysisReport report) {
        StringBuilder out = new StringBuilder(verdict(report));
        if (report.lowConfidence() && report.overall() != AnalysisReport.Status.UNREADABLE) {
            out.append("\n\n").append(resources.getString(R.string.low_confidence_warning));
        }
        if (!report.results().isEmpty()) {
            out.append("\n\n").append(resources.getString(R.string.section_allergens));
            for (AllergenResult result : report.results()) {
                out.append('\n').append(result.allergen().label())
                        .append(" — ").append(statusWord(result.status()));
                for (Finding finding : result.findings()) {
                    out.append("\n    • ").append(describe(finding));
                }
            }
        }
        if (report.overall() != AnalysisReport.Status.UNREADABLE) {
            appendIngredients(out, report);
        }
        return out.append("\n\n").append(resources.getString(R.string.disclaimer)).toString();
    }

    private String verdict(AnalysisReport report) {
        AnalysisReport.Status overall = report.overall();
        switch (overall) {
            case CONTAINS:
                return resources.getString(R.string.status_contains_message, names(report, overall));
            case MAY_CONTAIN:
                return resources.getString(R.string.status_may_contain_message, names(report, overall));
            case POSSIBLE:
                return resources.getString(R.string.status_possible_message, names(report, overall));
            case NOT_DETECTED:
                return resources.getString(R.string.status_not_detected_message);
            case UNREADABLE:
                return resources.getString(R.string.status_unreadable_message);
            default:
                return resources.getString(R.string.status_no_allergens_message);
        }
    }

    private void appendIngredients(StringBuilder out, AnalysisReport report) {
        List<IngredientItem> items = report.label().ingredients();
        if (items.isEmpty()) {
            out.append("\n\n").append(resources.getString(R.string.no_ingredient_list));
            return;
        }
        int topLevel = 0;
        for (IngredientItem item : items) {
            if (item.depth() == 0) {
                topLevel++;
            }
        }
        out.append("\n\n").append(resources.getString(R.string.section_ingredients, topLevel));
        for (IngredientItem item : items) {
            // Sub-ingredients are already quoted inside their parent, so only indent them.
            out.append('\n');
            for (int i = 0; i < item.depth(); i++) {
                out.append("    ");
            }
            out.append("• ").append(item.original());
        }
    }

    /** The allergens that ended up with this exact status, in report order. */
    private static String names(AnalysisReport report, AnalysisReport.Status status) {
        List<String> names = new ArrayList<>();
        for (AllergenResult result : report.results()) {
            if (result.status() == status) {
                names.add(result.allergen().label());
            }
        }
        return String.join(", ", names);
    }

    private String statusWord(AnalysisReport.Status status) {
        switch (status) {
            case CONTAINS:
                return resources.getString(R.string.result_contains);
            case MAY_CONTAIN:
                return resources.getString(R.string.result_may_contain);
            case POSSIBLE:
                return resources.getString(R.string.result_possible);
            case UNREADABLE:
                return resources.getString(R.string.result_unreadable);
            default:
                return resources.getString(R.string.result_not_detected);
        }
    }

    private String describe(Finding finding) {
        int template;
        switch (finding.source()) {
            case CONTAINS_STATEMENT:
                template = R.string.evidence_contains_statement;
                break;
            case MAY_CONTAIN_STATEMENT:
                template = R.string.evidence_may_contain_statement;
                break;
            default:
                template = R.string.evidence_ingredient;
                break;
        }
        String line = resources.getString(template, finding.evidence(), finding.matchedTerm());
        switch (finding.reason()) {
            case AMBIGUOUS_INGREDIENT:
                return line + " — " + resources.getString(R.string.reason_ambiguous);
            case UNCERTAIN_READ:
                return line + " — " + resources.getString(R.string.reason_uncertain_read);
            default:
                return line;
        }
    }
}
