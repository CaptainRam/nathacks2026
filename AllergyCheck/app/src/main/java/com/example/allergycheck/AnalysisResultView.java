package com.example.allergycheck;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills the in-page result (view_analysis.xml) from an {@link AnalysisReport}: a verdict card
 * coloured by severity, one row per selected allergen with the label text behind it, the
 * ingredients that were read and the disclaimer.
 */
final class AnalysisResultView {

    private final ViewGroup root;
    private final MaterialCardView verdictCard;
    private final ImageView verdictIcon;
    private final TextView verdictTitle;
    private final TextView verdictMessage;
    private final TextView lowConfidenceNote;
    private final View allergenTitle;
    private final LinearLayout allergenRows;
    private final TextView ingredientsTitle;
    private final View ingredientsCard;
    private final TextView ingredientsText;
    private final AnalysisMessage message;
    /** Stable reading nodes: display preference changes update the summary and existing rows. */
    private final Map<String, View> rowsByAllergen = new HashMap<>();
    @Nullable private AnalysisReport displayedReport;

    AnalysisResultView(@NonNull ViewGroup root) {
        this.root = root;
        verdictCard = root.findViewById(R.id.verdict_card);
        verdictIcon = root.findViewById(R.id.verdict_icon);
        verdictTitle = root.findViewById(R.id.verdict_title);
        verdictMessage = root.findViewById(R.id.verdict_message);
        lowConfidenceNote = root.findViewById(R.id.low_confidence_note);
        allergenTitle = root.findViewById(R.id.allergen_section_title);
        allergenRows = root.findViewById(R.id.allergen_rows);
        // A new renderer may take over the same subtree; retain its existing reading nodes.
        for (int index = 0; index < allergenRows.getChildCount(); index++) {
            View row = allergenRows.getChildAt(index);
            Object id = row.getTag(R.id.allergen_name);
            if (id instanceof String) rowsByAllergen.put((String) id, row);
        }
        ingredientsTitle = root.findViewById(R.id.ingredients_section_title);
        ingredientsCard = root.findViewById(R.id.ingredients_card);
        ingredientsText = root.findViewById(R.id.ingredients_text);
        message = new AnalysisMessage(root.getResources());
    }

    /**
     * Shows a full analysis. Allergens that were not detected are listed only when
     * {@code showNotDetected} is set; the verdict still covers every selected allergen.
     */
    void showReport(@NonNull AnalysisReport report, boolean showNotDetected) {
        displayedReport = report;
        bindVerdict(report.overall(), message.title(report), message.verdict(report,
                AllergenPreferences.mayContainRedWarning(root.getContext())));
        String reportNote = message.reportNote(report);
        setTextIfChanged(lowConfidenceNote, reportNote);
        lowConfidenceNote.setVisibility(reportNote.isEmpty() ? View.GONE : View.VISIBLE);

        List<AllergenResult> displayed = new ArrayList<>();
        Set<String> displayedIds = new HashSet<>();
        for (AllergenResult result : report.results()) {
            if (showNotDetected || result.status() != AnalysisReport.Status.NOT_DETECTED) {
                displayed.add(result);
                displayedIds.add(result.allergen().id());
            }
        }
        // Remove hidden/obsolete rows first, so surviving rows can shift without being detached.
        for (String id : new ArrayList<>(rowsByAllergen.keySet())) {
            if (!displayedIds.contains(id)) {
                allergenRows.removeView(rowsByAllergen.remove(id));
            }
        }
        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        int shown = 0;
        for (AllergenResult result : displayed) {
            String id = result.allergen().id();
            View row = rowsByAllergen.get(id);
            if (row == null) {
                row = inflater.inflate(R.layout.item_allergen_result, allergenRows, false);
                row.setTag(R.id.allergen_name, id);
                rowsByAllergen.put(id, row);
            }
            setTextIfChanged(row.findViewById(R.id.allergen_name), result.allergen().label());
            TextView status = row.findViewById(R.id.allergen_status);
            setTextIfChanged(status, message.statusWord(result.status()));
            Tone tone = tone(result.status());
            status.setBackgroundTintList(ColorStateList.valueOf(tone.container));
            status.setTextColor(tone.onContainer);
            TextView evidence = row.findViewById(R.id.allergen_evidence);
            String lines = message.evidence(result);
            setTextIfChanged(evidence, lines);
            evidence.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
            if (row.getParent() == null) {
                allergenRows.addView(row, shown);
            } else if (allergenRows.indexOfChild(row) != shown) {
                allergenRows.removeView(row);
                allergenRows.addView(row, shown);
            }
            shown++;
        }
        boolean hasAllergens = shown > 0;
        allergenTitle.setVisibility(hasAllergens ? View.VISIBLE : View.GONE);
        allergenRows.setVisibility(hasAllergens ? View.VISIBLE : View.GONE);

        boolean hasIngredients = AnalysisMessage.showIngredients(report);
        setTextIfChanged(ingredientsTitle, message.ingredientsTitle(report));
        setTextIfChanged(ingredientsText, message.ingredients(report));
        ingredientsTitle.setVisibility(hasIngredients ? View.VISIBLE : View.GONE);
        ingredientsCard.setVisibility(hasIngredients ? View.VISIBLE : View.GONE);
    }

    /** Shows a result that is only a headline and a message, e.g. when no text was found. */
    void showMessage(@NonNull String title, @NonNull String body) {
        displayedReport = null;
        bindVerdict(AnalysisReport.Status.UNREADABLE, title, body);
        lowConfidenceNote.setVisibility(View.GONE);
        allergenRows.removeAllViews();
        rowsByAllergen.clear();
        allergenTitle.setVisibility(View.GONE);
        allergenRows.setVisibility(View.GONE);
        ingredientsTitle.setVisibility(View.GONE);
        ingredientsCard.setVisibility(View.GONE);
    }

    /** Refresh the summary, colors and filtering without rerunning or saving the analysis. */
    void refreshDisplayPreferences() {
        if (displayedReport != null) {
            showReport(displayedReport, AllergenPreferences.showNotDetected(root.getContext()));
        }
    }

    @NonNull
    CharSequence title() {
        return verdictTitle.getText();
    }

    /** The verdict icon, so the caller can animate it in. */
    @NonNull
    View verdictIcon() {
        return verdictIcon;
    }

    private void bindVerdict(AnalysisReport.Status status, String title, String body) {
        Tone tone = tone(status);
        verdictCard.setCardBackgroundColor(tone.container);
        setTextIfChanged(verdictTitle, title);
        verdictTitle.setTextColor(tone.onContainer);
        setTextIfChanged(verdictMessage, body);
        verdictMessage.setTextColor(tone.onContainer);
        verdictIcon.setImageResource(tone.icon);
        // Inverted colours make the icon read as a badge on the tinted card.
        verdictIcon.setBackgroundTintList(ColorStateList.valueOf(tone.onContainer));
        verdictIcon.setImageTintList(ColorStateList.valueOf(tone.container));
    }

    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private Tone tone(AnalysisReport.Status status) {
        switch (status) {
            case CONTAINS:
                return new Tone(attr(com.google.android.material.R.attr.colorErrorContainer),
                        attr(com.google.android.material.R.attr.colorOnErrorContainer),
                        R.drawable.ic_warning);
            case MAY_CONTAIN:
                if (AllergenPreferences.mayContainRedWarning(root.getContext())) {
                    return new Tone(attr(com.google.android.material.R.attr.colorErrorContainer),
                            attr(com.google.android.material.R.attr.colorOnErrorContainer),
                            R.drawable.ic_warning);
                }
            case POSSIBLE:
                return new Tone(color(R.color.status_warning_container),
                        color(R.color.status_on_warning_container), R.drawable.ic_warning);
            case NOT_DETECTED:
                return new Tone(attr(com.google.android.material.R.attr.colorPrimaryContainer),
                        attr(com.google.android.material.R.attr.colorOnPrimaryContainer),
                        R.drawable.ic_check);
            default:
                // Unreadable or nothing to check: neutral, never styled like a clear result.
                return new Tone(attr(com.google.android.material.R.attr.colorSurfaceContainerHigh),
                        attr(com.google.android.material.R.attr.colorOnSurface),
                        R.drawable.ic_info);
        }
    }

    @ColorInt
    private int attr(@AttrRes int attribute) {
        return MaterialColors.getColor(root, attribute);
    }

    @ColorInt
    private int color(int resource) {
        return ContextCompat.getColor(root.getContext(), resource);
    }

    private static final class Tone {
        @ColorInt final int container;
        @ColorInt final int onContainer;
        @DrawableRes final int icon;

        Tone(@ColorInt int container, @ColorInt int onContainer, @DrawableRes int icon) {
            this.container = container;
            this.onContainer = onContainer;
            this.icon = icon;
        }
    }
}
