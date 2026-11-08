package com.example.allergycheck;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.ingredients.LocalModelManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    /** Boolean extra: open on the AI model section instead of allergens. */
    static final String EXTRA_SHOW_MODEL = "show_model";
    private static final String DRAFT_ALLERGENS = "draft_allergens";
    private static final String MODEL_DETAILS_EXPANDED = "model_details_expanded";
    private static final String MODEL_SECTION_SHOWN = "model_section_shown";

    /**
     * Allergen ids. Only ids the dictionary defines ever enter this set, so its size is the
     * number actually checked — which is what {@link #updateSelectionSummary} reports.
     */
    private final Set<String> selectedAllergens = new HashSet<>();
    /** What is stored now; the draft differs from it until Save is pressed. */
    private final Set<String> savedAllergens = new HashSet<>();
    private TextView selectionSummary;
    private NestedScrollView scroll;
    private View headerDivider;
    private View allergenSection;
    private View modelSection;
    private View saveBar;
    private LocalModelManager modelManager;
    private TextView modelAvailability;
    private TextView modelStatus;
    private LinearProgressIndicator modelProgress;
    private MaterialButton modelDownloadButton;
    private MaterialButton modelDeleteButton;
    private View modelDetails;
    private View modelTerms;
    private MaterialButton modelDetailsButton;
    /** Pill colors, resolved once: they only differ between FAILED and every other state. */
    private ColorStateList pillBackground;
    private ColorStateList pillFailedBackground;
    private int pillText;
    private int pillFailedText;
    private final LocalModelManager.Listener modelListener = this::renderModelStatus;

    /** Leaving with an unsaved allergen draft asks first instead of dropping it silently. */
    private final OnBackPressedCallback unsavedChangesCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            FragmentManager fragments = getSupportFragmentManager();
            // showNow commits at once, so a fast second tap finds this one and adds no other.
            if (fragments.findFragmentByTag(DiscardChangesDialog.TAG) == null
                    && !fragments.isStateSaved()) {
                new DiscardChangesDialog().showNow(fragments, DiscardChangesDialog.TAG);
            }
        }
    };

    /** A fragment, so the question survives rotation instead of leaking its window. */
    public static class DiscardChangesDialog extends DialogFragment {
        static final String TAG = "discard_changes_dialog";

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            SettingsActivity activity = (SettingsActivity) requireActivity();
            return new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.discard_changes_title)
                    .setMessage(R.string.discard_changes_message)
                    .setPositiveButton(R.string.save, (dialog, which) -> activity.savePreferences())
                    .setNegativeButton(R.string.discard, (dialog, which) -> activity.finish())
                    .setNeutralButton(R.string.keep_editing, null)
                    .create();
        }
    }

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

        getOnBackPressedDispatcher().addCallback(this, unsavedChangesCallback);
        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());

        scroll = findViewById(R.id.settings_scroll);
        headerDivider = findViewById(R.id.settings_header_divider);
        scroll.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener)
                (view, x, y, oldX, oldY) -> updateHeaderDivider());
        allergenSection = findViewById(R.id.allergen_section);
        modelSection = findViewById(R.id.model_section);
        saveBar = findViewById(R.id.settings_save_bar);
        selectionSummary = findViewById(R.id.selection_summary);

        modelManager = LocalModelManager.get(this);
        modelAvailability = findViewById(R.id.model_availability);
        pillBackground = ColorStateList.valueOf(MaterialColors.getColor(modelAvailability,
                com.google.android.material.R.attr.colorPrimaryContainer));
        pillFailedBackground = ColorStateList.valueOf(MaterialColors.getColor(modelAvailability,
                com.google.android.material.R.attr.colorErrorContainer));
        pillText = MaterialColors.getColor(modelAvailability,
                com.google.android.material.R.attr.colorOnPrimaryContainer);
        pillFailedText = MaterialColors.getColor(modelAvailability,
                com.google.android.material.R.attr.colorOnErrorContainer);
        modelStatus = findViewById(R.id.model_download_status);
        modelProgress = findViewById(R.id.model_download_progress);
        modelDownloadButton = findViewById(R.id.model_download_button);
        modelDeleteButton = findViewById(R.id.model_delete_button);
        modelDetails = findViewById(R.id.model_details);
        modelDetailsButton = findViewById(R.id.model_details_button);
        setModelDetailsExpanded(savedInstanceState != null
                && savedInstanceState.getBoolean(MODEL_DETAILS_EXPANDED));
        modelDetailsButton.setOnClickListener(view ->
                setModelDetailsExpanded(modelDetails.getVisibility() != View.VISIBLE));
        ((TextView) findViewById(R.id.model_download_source))
                .setMovementMethod(LinkMovementMethod.getInstance());
        modelTerms = findViewById(R.id.model_terms_link);
        ((TextView) modelTerms).setMovementMethod(LinkMovementMethod.getInstance());
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

        AllergenDictionary dictionary = AllergenDictionaryProvider.get(this);
        savedAllergens.addAll(AllergenPreferences.selectedAllergenIds(this, dictionary));
        ArrayList<String> draft = savedInstanceState == null ? null
                : savedInstanceState.getStringArrayList(DRAFT_ALLERGENS);
        selectedAllergens.addAll(draft == null ? savedAllergens : draft);

        ChipGroup options = findViewById(R.id.allergen_options_container);
        LayoutInflater inflater = getLayoutInflater();
        for (Allergen allergen : dictionary.allergens()) {
            // The id is what is stored and counted; the label is only the chip text.
            final String id = allergen.id();
            Chip chip = (Chip) inflater.inflate(R.layout.item_allergen_chip, options, false);
            chip.setText(allergen.label());
            // Draft selection is restored explicitly so generated chip IDs are unnecessary.
            chip.setSaveEnabled(false);
            chip.setChecked(selectedAllergens.contains(id));
            chip.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedAllergens.add(id);
                } else {
                    selectedAllergens.remove(id);
                }
                updateSelectionSummary();
            });
            options.addView(chip);
        }
        updateSelectionSummary();

        findViewById(R.id.save_preferences_button).setOnClickListener(view -> savePreferences());

        MaterialButtonToggleGroup sections = findViewById(R.id.settings_section_toggle);
        boolean showModel = savedInstanceState != null
                ? savedInstanceState.getBoolean(MODEL_SECTION_SHOWN)
                : getIntent().getBooleanExtra(EXTRA_SHOW_MODEL, false);
        sections.check(showModel ? R.id.section_model_button : R.id.section_allergens_button);
        showSection(showModel);
        sections.addOnButtonCheckedListener((group, buttonId, checked) -> {
            if (checked) {
                showSection(buttonId == R.id.section_model_button);
                scroll.scrollTo(0, 0);
            }
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

    /** Shows one section; the save bar belongs to allergens because model actions apply at once. */
    private void showSection(boolean model) {
        allergenSection.setVisibility(model ? View.GONE : View.VISIBLE);
        modelSection.setVisibility(model ? View.VISIBLE : View.GONE);
        saveBar.setVisibility(model ? View.GONE : View.VISIBLE);
        scroll.post(this::updateHeaderDivider);
    }

    private void updateHeaderDivider() {
        headerDivider.setVisibility(scroll.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
    }

    private void savePreferences() {
        AllergenPreferences.saveSelectedAllergenIds(this, selectedAllergens);
        setResult(RESULT_OK);
        finish();
    }

    private void renderModelStatus(LocalModelManager.Status status) {
        boolean installed = status.isInstalled();
        boolean downloading = status.state == LocalModelManager.State.DOWNLOADING
                || status.state == LocalModelManager.State.PAUSED;
        boolean installing = status.state == LocalModelManager.State.INSTALLING;
        boolean failed = status.state == LocalModelManager.State.FAILED;
        modelAvailability.setText(badgeText(status, installed));
        modelAvailability.setBackgroundTintList(failed ? pillFailedBackground : pillBackground);
        modelAvailability.setTextColor(failed ? pillFailedText : pillText);
        modelDownloadButton.setVisibility(installed ? View.GONE : View.VISIBLE);
        modelDownloadButton.setEnabled(!installed && !downloading && !installing);
        modelDownloadButton.setText(failed
                ? R.string.model_settings_retry : R.string.model_settings_download);
        modelDeleteButton.setEnabled(installed && !downloading && !installing);
        modelDeleteButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        // The terms ask to be read before downloading, so they only matter until it is installed.
        modelTerms.setVisibility(installed ? View.GONE : View.VISIBLE);
        // "Installed" already says it all; the line returns for progress, errors and hints.
        modelStatus.setVisibility(status.state == LocalModelManager.State.READY
                ? View.GONE : View.VISIBLE);

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

    private int badgeText(LocalModelManager.Status status, boolean installed) {
        switch (status.state) {
            case DOWNLOADING:
                return R.string.model_settings_badge_downloading;
            case PAUSED:
                return R.string.model_settings_badge_paused;
            case INSTALLING:
                return R.string.model_settings_badge_installing;
            case FAILED:
                return R.string.model_settings_badge_failed;
            default:
                return installed ? R.string.model_settings_installed : R.string.model_settings_missing;
        }
    }

    private void setModelDetailsExpanded(boolean expanded) {
        modelDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
        modelDetailsButton.setText(expanded ? R.string.model_settings_hide_details
                : R.string.model_settings_details);
        modelDetailsButton.setIconResource(expanded ? R.drawable.ic_expand_less
                : R.drawable.ic_expand_more);
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
        unsavedChangesCallback.setEnabled(!selectedAllergens.equals(savedAllergens));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putStringArrayList(DRAFT_ALLERGENS, new ArrayList<>(selectedAllergens));
        outState.putBoolean(MODEL_DETAILS_EXPANDED, modelDetails.getVisibility() == View.VISIBLE);
        outState.putBoolean(MODEL_SECTION_SHOWN, modelSection.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(outState);
    }
}
