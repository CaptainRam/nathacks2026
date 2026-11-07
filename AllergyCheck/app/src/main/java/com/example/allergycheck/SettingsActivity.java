package com.example.allergycheck;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.allergycheck.ingredients.LocalModelManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    private static final String DRAFT_ALLERGENS = "draft_allergens";

    private final Set<String> selectedAllergens = new HashSet<>();
    private TextView selectionSummary;
    private LocalModelManager modelManager;
    private TextView modelAvailability;
    private TextView modelStatus;
    private LinearProgressIndicator modelProgress;
    private MaterialButton modelDownloadButton;
    private MaterialButton modelDeleteButton;
    private final LocalModelManager.Listener modelListener = this::renderModelStatus;

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
        modelManager = LocalModelManager.get(this);
        modelAvailability = findViewById(R.id.model_availability);
        modelStatus = findViewById(R.id.model_download_status);
        modelProgress = findViewById(R.id.model_download_progress);
        modelDownloadButton = findViewById(R.id.model_download_button);
        modelDeleteButton = findViewById(R.id.model_delete_button);
        ((TextView) findViewById(R.id.model_download_source))
                .setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView) findViewById(R.id.model_terms_link))
                .setMovementMethod(LinkMovementMethod.getInstance());
        modelDownloadButton.setOnClickListener(view -> modelManager.download());
        modelDeleteButton.setOnClickListener(view ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.model_settings_delete_title)
                        .setMessage(R.string.model_settings_delete_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.model_settings_delete,
                                (dialog, which) -> modelManager.delete())
                        .show());
        renderModelStatus(modelManager.getStatus());

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

    @Override
    protected void onStart() {
        super.onStart();
        modelManager.addListener(modelListener);
    }

    @Override
    protected void onStop() {
        modelManager.removeListener(modelListener);
        super.onStop();
    }

    private void renderModelStatus(LocalModelManager.Status status) {
        boolean installed = status.isInstalled();
        boolean downloading = status.state == LocalModelManager.State.DOWNLOADING
                || status.state == LocalModelManager.State.PAUSED;
        boolean installing = status.state == LocalModelManager.State.INSTALLING;
        modelAvailability.setText(installed ? R.string.model_settings_installed
                : R.string.model_settings_missing);
        modelDownloadButton.setEnabled(!installed && !downloading && !installing);
        modelDownloadButton.setText(status.state == LocalModelManager.State.FAILED
                ? R.string.model_settings_retry : R.string.model_settings_download);
        modelDeleteButton.setEnabled(installed && !downloading && !installing);

        modelProgress.setVisibility(View.GONE);
        if (downloading || installing) {
            modelProgress.setIndeterminate(installing || status.totalBytes <= 0);
            if (!installing && status.totalBytes > 0) {
                int percent = (int) Math.max(0, Math.min(100,
                        Math.round(100d * status.downloadedBytes / status.totalBytes)));
                modelProgress.setProgressCompat(percent, false);
            }
            modelProgress.setVisibility(View.VISIBLE);
        }

        switch (status.state) {
            case READY:
                modelStatus.setText(R.string.model_settings_ready);
                break;
            case DOWNLOADING:
                modelStatus.setText(downloadProgress(status));
                break;
            case PAUSED:
                modelStatus.setText(status.error == null
                        ? getString(R.string.model_settings_waiting, downloadProgress(status)) : status.error);
                break;
            case INSTALLING:
                modelStatus.setText(R.string.model_settings_installing);
                break;
            case FAILED:
                modelStatus.setText(status.error == null || status.error.trim().isEmpty()
                        ? getString(R.string.model_settings_failed)
                        : getString(R.string.model_settings_failed_reason, status.error));
                break;
            case MISSING:
            default:
                modelStatus.setText(R.string.model_settings_not_downloaded);
                break;
        }
    }

    private String downloadProgress(LocalModelManager.Status status) {
        String downloaded = Formatter.formatFileSize(this, Math.max(0, status.downloadedBytes));
        if (status.totalBytes <= 0) {
            return getString(R.string.model_settings_downloading_unknown, downloaded);
        }
        int percent = (int) Math.max(0, Math.min(100,
                Math.round(100d * status.downloadedBytes / status.totalBytes)));
        return getString(R.string.model_settings_downloading, percent, downloaded,
                Formatter.formatFileSize(this, status.totalBytes));
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
