package com.example.allergycheck;

import android.content.res.ColorStateList;
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
import androidx.core.content.ContextCompat;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

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
    private final View lowConfidenceNote;
    private final View allergenTitle;
    private final LinearLayout allergenRows;
    private final TextView ingredientsTitle;
    private final View ingredientsCard;
    private final TextView ingredientsText;
    private final AnalysisMessage message;

    AnalysisResultView(@NonNull ViewGroup root) {
        this.root = root;
        verdictCard = root.findViewById(R.id.verdict_card);
        verdictIcon = root.findViewById(R.id.verdict_icon);
        verdictTitle = root.findViewById(R.id.verdict_title);
        verdictMessage = root.findViewById(R.id.verdict_message);
        lowConfidenceNote = root.findViewById(R.id.low_confidence_note);
        allergenTitle = root.findViewById(R.id.allergen_section_title);
        allergenRows = root.findViewById(R.id.allergen_rows);
        ingredientsTitle = root.findViewById(R.id.ingredients_section_title);
        ingredientsCard = root.findViewById(R.id.ingredients_card);
        ingredientsText = root.findViewById(R.id.ingredients_text);
        message = new AnalysisMessage(root.getResources());
    }

    /** Shows a full analysis. */
    void showReport(@NonNull AnalysisReport report) {
        bindVerdict(report.overall(), message.title(report), message.verdict(report));
        lowConfidenceNote.setVisibility(
                AnalysisMessage.showLowConfidence(report) ? View.VISIBLE : View.GONE);

        allergenRows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        for (AllergenResult result : report.results()) {
            View row = inflater.inflate(R.layout.item_allergen_result, allergenRows, false);
            ((TextView) row.findViewById(R.id.allergen_name)).setText(result.allergen().label());
            TextView status = row.findViewById(R.id.allergen_status);
            status.setText(message.statusWord(result.status()));
            Tone tone = tone(result.status());
            status.setBackgroundTintList(ColorStateList.valueOf(tone.container));
            status.setTextColor(tone.onContainer);
            TextView evidence = row.findViewById(R.id.allergen_evidence);
            String lines = message.evidence(result);
            evidence.setText(lines);
            evidence.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
            allergenRows.addView(row);
        }
        boolean hasAllergens = !report.results().isEmpty();
        allergenTitle.setVisibility(hasAllergens ? View.VISIBLE : View.GONE);
        allergenRows.setVisibility(hasAllergens ? View.VISIBLE : View.GONE);

        boolean hasIngredients = AnalysisMessage.showIngredients(report);
        ingredientsTitle.setText(message.ingredientsTitle(report));
        ingredientsText.setText(message.ingredients(report));
        ingredientsTitle.setVisibility(hasIngredients ? View.VISIBLE : View.GONE);
        ingredientsCard.setVisibility(hasIngredients ? View.VISIBLE : View.GONE);
    }

    /** Shows a result that is only a headline and a message, e.g. when no text was found. */
    void showMessage(@NonNull String title, @NonNull String body) {
        bindVerdict(AnalysisReport.Status.UNREADABLE, title, body);
        lowConfidenceNote.setVisibility(View.GONE);
        allergenRows.removeAllViews();
        allergenTitle.setVisibility(View.GONE);
        allergenRows.setVisibility(View.GONE);
        ingredientsTitle.setVisibility(View.GONE);
        ingredientsCard.setVisibility(View.GONE);
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
        verdictTitle.setText(title);
        verdictTitle.setTextColor(tone.onContainer);
        verdictMessage.setText(body);
        verdictMessage.setTextColor(tone.onContainer);
        verdictIcon.setImageResource(tone.icon);
        // Inverted colours make the icon read as a badge on the tinted card.
        verdictIcon.setBackgroundTintList(ColorStateList.valueOf(tone.onContainer));
        verdictIcon.setImageTintList(ColorStateList.valueOf(tone.container));
    }

    private Tone tone(AnalysisReport.Status status) {
        switch (status) {
            case CONTAINS:
                return new Tone(attr(com.google.android.material.R.attr.colorErrorContainer),
                        attr(com.google.android.material.R.attr.colorOnErrorContainer),
                        R.drawable.ic_warning);
            case MAY_CONTAIN:
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
