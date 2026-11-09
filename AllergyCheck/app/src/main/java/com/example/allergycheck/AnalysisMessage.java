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
 * Words an {@link AnalysisReport} for the result shown on the home page: the verdict, the
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
        if (onlyIncompleteAi(report)) {
            return resources.getString(R.string.status_ai_incomplete_title);
        }
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

    /** The verdict explanation, including precautionary findings that share the red card. */
    @NonNull
    String verdict(@NonNull AnalysisReport report, boolean mayContainRedWarning) {
        if (onlyIncompleteAi(report)) {
            return resources.getString(R.string.status_ai_incomplete_message);
        }
        AnalysisReport.Status overall = report.overall();
        switch (overall) {
            case CONTAINS:
                String contains = resources.getString(R.string.status_contains_message,
                        names(report, overall));
                String precautionary = names(report, AnalysisReport.Status.MAY_CONTAIN);
                if (mayContainRedWarning && !precautionary.isEmpty()) {
                    return contains + "\n\n" + resources.getString(
                            R.string.status_may_contain_message, precautionary);
                }
                return contains;
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

    /** Whether to warn that the result rests on thin evidence. */
    static boolean showLowConfidence(@NonNull AnalysisReport report) {
        return report.lowConfidence() && report.overall() != AnalysisReport.Status.UNREADABLE;
    }

    /** Model inferences and failed checks stay visible even when unmatched allergens are hidden. */
    @NonNull
    String reportNote(@NonNull AnalysisReport report) {
        List<String> notes = new ArrayList<>();
        if (report.aiIncomplete()) {
            int reason = failureNote(report.aiFailure());
            if (reason != 0) notes.add(resources.getString(reason));
        }
        if (report.aiAnalysis()) {
            notes.add(resources.getString(report.aiIncomplete()
                    ? R.string.ai_analysis_incomplete_note : R.string.ai_analysis_note));
        }
        if (showLowConfidence(report)) {
            notes.add(resources.getString(R.string.low_confidence_warning));
        }
        return String.join("\n\n", notes);
    }

    private static int failureNote(AnalysisReport.AiFailure failure) {
        switch (failure) {
            case AUTHENTICATION: return R.string.ai_failure_authentication;
            case QUOTA: return R.string.ai_failure_quota;
            case MODEL: return R.string.ai_failure_model;
            case RATE_LIMIT: return R.string.ai_failure_rate_limit;
            case UNAVAILABLE: return R.string.ai_failure_unavailable;
            case TIMEOUT: return R.string.ai_failure_timeout;
            case INVALID_RESPONSE: return R.string.ai_failure_invalid_response;
            default: return 0;
        }
    }

    /** An unavailable model must not produce an empty "might involve:" allergen list. */
    private static boolean onlyIncompleteAi(AnalysisReport report) {
        if (!report.aiIncomplete()) {
            return false;
        }
        switch (report.overall()) {
            case UNREADABLE:
                return true;
            case POSSIBLE:
                for (AllergenResult result : report.results()) {
                    if (result.status() == AnalysisReport.Status.POSSIBLE) {
                        return false;
                    }
                }
                return true;
            default:
                return false;
        }
    }

    /** A failed model check still shows every ingredient that was successfully read. */
    static boolean showIngredients(@NonNull AnalysisReport report) {
        return report.aiIncomplete() || report.overall() != AnalysisReport.Status.UNREADABLE;
    }

    /** Heading for the ingredient list, with the number of top-level ingredients. */
    @NonNull
    String ingredientsTitle(@NonNull AnalysisReport report) {
        int topLevel = 0;
        for (IngredientItem item : report.label().ingredients()) {
            if (item.depth() == 0) {
                topLevel++;
            }
        }
        return resources.getString(R.string.section_ingredients, topLevel);
    }

    /** One bullet per ingredient, sub-ingredients indented; or a note when none were found. */
    @NonNull
    String ingredients(@NonNull AnalysisReport report) {
        List<IngredientItem> items = report.label().ingredients();
        if (items.isEmpty()) {
            return resources.getString(R.string.no_ingredient_list);
        }
        StringBuilder out = new StringBuilder();
        for (IngredientItem item : items) {
            if (out.length() > 0) {
                out.append('\n');
            }
            // Sub-ingredients are already quoted inside their parent, so only indent them.
            for (int i = 0; i < item.depth(); i++) {
                out.append("    ");
            }
            out.append("• ").append(item.original());
        }
        return out.toString();
    }

    /** The label text behind one allergen's status, one bullet per finding. */
    @NonNull
    String evidence(@NonNull AllergenResult result) {
        StringBuilder out = new StringBuilder();
        for (Finding finding : result.findings()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("• ").append(describe(finding));
        }
        return out.toString();
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

    @NonNull
    String statusWord(@NonNull AnalysisReport.Status status) {
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
        int aiTemplate = 0;
        switch (finding.reason()) {
            case AI_RISK:
                aiTemplate = R.string.evidence_ai_risk;
                break;
            case AI_UNCERTAIN:
                aiTemplate = R.string.evidence_ai_uncertain;
                break;
            case AI_UNAVAILABLE:
                aiTemplate = R.string.evidence_ai_unavailable;
                break;
            default:
                break;
        }
        if (aiTemplate != 0) {
            String line = resources.getString(aiTemplate, finding.evidence(), finding.allergen().label());
            if (finding.score() != null) {
                line += " — " + resources.getString(R.string.evidence_ai_score, finding.score() * 100d);
            }
            return line;
        }
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
