package com.example.allergycheck;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.allergycheck.analysis.AllergenResult;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Finding;
import com.example.allergycheck.analysis.IngredientItem;
import com.example.allergycheck.analysis.ParsedLabel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows an {@link AnalysisReport}: an overall verdict banner, a per-allergen breakdown with the
 * label text that triggered each finding, the statements found, and the structured ingredient
 * list with matches highlighted.
 *
 * <p>Start it with {@link #newIntent}. "Not detected" is always worded as "not found in the text
 * that was read" and an unreadable label gets its own state — neither is ever shown as a clear result.
 */
public class ResultsActivity extends AppCompatActivity {

    private static final String EXTRA_REPORT = "report";

    public static Intent newIntent(Context context, AnalysisReport report) {
        return new Intent(context, ResultsActivity.class).putExtra(EXTRA_REPORT, report);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_results);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.results_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        AnalysisReport report = getIntent().getSerializableExtra(EXTRA_REPORT, AnalysisReport.class);
        if (report == null) {
            finish();
            return;
        }
        bindBanner(report);
        bindAllergens(report);
        bindStatements(report.label());
        bindIngredients(report);
        bindRawText(report.label());
        findViewById(R.id.retry_button).setOnClickListener(v -> finish());
    }

    // ---- banner ---------------------------------------------------------------------------

    private void bindBanner(AnalysisReport report) {
        int title;
        int message;
        int color;
        switch (report.overall()) {
            case CONTAINS:
                title = R.string.status_contains_title;
                message = R.string.status_contains_message;
                color = R.color.status_contains;
                break;
            case MAY_CONTAIN:
                title = R.string.status_may_contain_title;
                message = R.string.status_may_contain_message;
                color = R.color.status_may_contain;
                break;
            case POSSIBLE:
                title = R.string.status_possible_title;
                message = R.string.status_possible_message;
                color = R.color.status_possible;
                break;
            case NOT_DETECTED:
                title = R.string.status_not_detected_title;
                message = R.string.status_not_detected_message;
                color = R.color.status_not_detected;
                break;
            case UNREADABLE:
                title = R.string.status_unreadable_title;
                message = R.string.status_unreadable_message;
                color = R.color.status_unreadable;
                break;
            default:
                title = R.string.status_no_allergens_title;
                message = R.string.status_no_allergens_message;
                color = R.color.status_neutral;
                break;
        }
        ((TextView) findViewById(R.id.banner_title)).setText(title);
        TextView messageView = findViewById(R.id.banner_message);
        messageView.setText(getString(message, namesWithStatus(report, report.overall())));
        findViewById(R.id.banner).setBackgroundColor(ContextCompat.getColor(this, color));
        findViewById(R.id.low_confidence).setVisibility(
                report.lowConfidence() && report.overall() != AnalysisReport.Status.UNREADABLE
                        ? View.VISIBLE : View.GONE);
    }

    private static String namesWithStatus(AnalysisReport report, AnalysisReport.Status status) {
        List<String> names = new ArrayList<>();
        for (AllergenResult result : report.results()) {
            if (result.status() == status) {
                names.add(result.allergen().label());
            }
        }
        return String.join(", ", names);
    }

    // ---- per-allergen breakdown -----------------------------------------------------------

    private void bindAllergens(AnalysisReport report) {
        LinearLayout container = findViewById(R.id.allergen_results);
        container.removeAllViews();
        findViewById(R.id.allergens_header).setVisibility(
                report.results().isEmpty() ? View.GONE : View.VISIBLE);

        for (AllergenResult result : report.results()) {
            container.addView(text(result.allergen().label() + " — " + statusWord(result.status()),
                    16, Typeface.BOLD, 12, 0));
            for (Finding finding : result.findings()) {
                container.addView(text("• " + describe(finding), 14, Typeface.NORMAL, 2, 12));
            }
        }
    }

    private String statusWord(AnalysisReport.Status status) {
        switch (status) {
            case CONTAINS:
                return getString(R.string.result_contains);
            case MAY_CONTAIN:
                return getString(R.string.result_may_contain);
            case POSSIBLE:
                return getString(R.string.result_possible);
            case NOT_DETECTED:
                return getString(R.string.result_not_detected);
            default:
                return getString(R.string.result_unreadable);
        }
    }

    private String describe(Finding finding) {
        String allergen = finding.allergen().label();
        String line;
        switch (finding.source()) {
            case CONTAINS_STATEMENT:
                line = getString(R.string.evidence_contains_statement, finding.evidence(), allergen);
                break;
            case MAY_CONTAIN_STATEMENT:
                line = getString(R.string.evidence_may_contain_statement, finding.evidence(), allergen);
                break;
            default:
                line = getString(R.string.evidence_ingredient, finding.evidence(), allergen);
                break;
        }
        switch (finding.reason()) {
            case AMBIGUOUS_INGREDIENT:
                return line + "\n" + getString(R.string.reason_ambiguous);
            case UNCERTAIN_READ:
                return line + "\n" + getString(R.string.reason_uncertain_read, finding.matchedTerm());
            default:
                return line;
        }
    }

    // ---- statements and ingredients -------------------------------------------------------

    private void bindStatements(@NonNull ParsedLabel label) {
        LinearLayout container = findViewById(R.id.statements_list);
        container.removeAllViews();
        findViewById(R.id.statements_header).setVisibility(
                label.statements().isEmpty() ? View.GONE : View.VISIBLE);
        for (ParsedLabel.Statement statement : label.statements()) {
            // Precautions and definite declarations are kept visibly distinct, per the README.
            int prefix = statement.kind() == ParsedLabel.Kind.CONTAINS
                    ? R.string.statement_contains_prefix : R.string.statement_may_contain_prefix;
            container.addView(text(getString(prefix) + ": " + statement.text(), 14, Typeface.NORMAL, 4, 0));
        }
    }

    private void bindIngredients(AnalysisReport report) {
        LinearLayout container = findViewById(R.id.ingredients_list);
        container.removeAllViews();

        // ingredient text → allergens it triggered
        Map<String, List<String>> flagged = new HashMap<>();
        for (AllergenResult result : report.results()) {
            for (Finding finding : result.findings()) {
                if (finding.source() == Finding.Source.INGREDIENT) {
                    List<String> names = flagged.get(finding.evidence());
                    if (names == null) {
                        names = new ArrayList<>();
                        flagged.put(finding.evidence(), names);
                    }
                    String name = finding.level() == Finding.Level.POSSIBLE
                            ? finding.allergen().label() + "?" : finding.allergen().label();
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        }

        List<IngredientItem> items = report.label().ingredients();
        if (items.isEmpty()) {
            container.addView(text(getString(R.string.no_ingredients_found), 14, Typeface.ITALIC, 4, 0));
            return;
        }
        for (IngredientItem item : items) {
            List<String> names = flagged.get(item.original());
            String label = "• " + item.original();
            TextView row = text(names == null ? label : label + "  →  " + String.join(", ", names),
                    14, names == null ? Typeface.NORMAL : Typeface.BOLD, 2, item.depth() * 20);
            if (names != null) {
                row.setBackgroundColor(ContextCompat.getColor(this, R.color.highlight_item_bg));
                row.setTextColor(ContextCompat.getColor(this, R.color.highlight_item_text));
                row.setPadding(dp(6), dp(4), dp(6), dp(4));
            }
            container.addView(row);
        }
    }

    private void bindRawText(ParsedLabel label) {
        TextView raw = findViewById(R.id.raw_text);
        raw.setText(label.rawText());
        com.google.android.material.button.MaterialButton toggle = findViewById(R.id.toggle_raw_text);
        toggle.setOnClickListener(v -> {
            boolean show = raw.getVisibility() != View.VISIBLE;
            raw.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? R.string.hide_raw_text : R.string.show_raw_text);
        });
    }

    // ---- helpers --------------------------------------------------------------------------

    private TextView text(String value, int sp, int style, int topDp, int startDp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTypeface(null, style);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        params.setMarginStart(dp(startDp));
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
