package com.example.allergycheck;

import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    private static final String DRAFT_ALLERGENS = "draft_allergens";

    private final Set<String> selectedAllergens = new HashSet<>();
    private TextView selectionSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_settings);

        boolean darkTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat systemBars = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        systemBars.setAppearanceLightStatusBars(!darkTheme);
        systemBars.setAppearanceLightNavigationBars(!darkTheme);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings), (view, insets) -> {
            Insets systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(systemBarInsets.left, systemBarInsets.top,
                    systemBarInsets.right, systemBarInsets.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        selectionSummary = findViewById(R.id.selection_summary);

        ArrayList<String> draft = savedInstanceState == null ? null
                : savedInstanceState.getStringArrayList(DRAFT_ALLERGENS);
        selectedAllergens.addAll(draft == null
                ? AllergenPreferences.selectedAllergens(this) : draft);

        LinearLayout options = findViewById(R.id.allergen_options_container);
        for (String option : getResources().getStringArray(R.array.allergen_options)) {
            MaterialCheckBox checkBox = new MaterialCheckBox(this);
            checkBox.setText(option);
            checkBox.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            checkBox.setMinHeight(dp(60));
            checkBox.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
            // Draft selection is restored explicitly so generated row IDs are unnecessary.
            checkBox.setSaveEnabled(false);
            checkBox.setChecked(selectedAllergens.contains(option));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedAllergens.add(option);
                } else {
                    selectedAllergens.remove(option);
                }
                updateSelectionSummary();
            });
            options.addView(checkBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        updateSelectionSummary();

        findViewById(R.id.save_preferences_button).setOnClickListener(view -> {
            AllergenPreferences.saveSelectedAllergens(this, selectedAllergens);
            setResult(RESULT_OK);
            finish();
        });
    }

    private void updateSelectionSummary() {
        int count = selectedAllergens.size();
        selectionSummary.setText(count == 0 ? getString(R.string.no_allergens_selected)
                : getResources().getQuantityString(R.plurals.allergens_selected, count, count));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putStringArrayList(DRAFT_ALLERGENS, new ArrayList<>(selectedAllergens));
        super.onSaveInstanceState(outState);
    }
}
