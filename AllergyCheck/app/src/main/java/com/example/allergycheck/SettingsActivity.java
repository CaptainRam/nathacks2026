package com.example.allergycheck;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;

import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    /** Boolean extra: open on the AI model section instead of allergens. */
    static final String EXTRA_SHOW_MODEL = "show_model";
    private static final String DRAFT_ALLERGENS = "draft_allergens";
    private static final String MODEL_DETAILS_EXPANDED = "model_details_expanded";
    private static final String MODEL_SECTION_SHOWN = "model_section_shown";
    private static final String DRAFT_SHOW_NOT_DETECTED = "draft_show_not_detected";
    private static final String DRAFT_MAY_CONTAIN_RED_WARNING = "draft_may_contain_red_warning";
    private static final String ANALYSIS_CONFIGURATION_CHANGED = "analysis_configuration_changed";

    /**
     * Allergen ids. Only ids the dictionary defines ever enter this set, so its size is the
     * number actually checked — which is what {@link #updateSelectionSummary} reports.
     */
    private final Set<String> selectedAllergens = new HashSet<>();
    /** What is stored now; the draft differs from it until Save is pressed. */
    private final Set<String> savedAllergens = new HashSet<>();
    /** Draft and stored value of the "show allergens not detected" switch. */
    private boolean showNotDetected;
    private boolean savedShowNotDetected;
    private boolean mayContainRedWarning;
    private boolean savedMayContainRedWarning;
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
    private EditText apiKeyInput;
    private EditText openRouterModelInput;
    private EditText foodTypeModelInput;
    private EditText decisionsModelInput;
    private EditText decisionsThresholdInput;
    private EditText cloudConcurrencyInput;
    private boolean renderingDetectionMode;
    /** True from a successful cloud save until the next edit. */
    private boolean cloudJustSaved;
    private boolean analysisConfigurationChanged;

    /** Keeps the unsaved key through rotation without putting credentials into saved state. */
    public static final class CloudDraft extends ViewModel {
        String apiKey;
    }
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
        analysisConfigurationChanged = savedInstanceState != null
                && savedInstanceState.getBoolean(ANALYSIS_CONFIGURATION_CHANGED);
        if (analysisConfigurationChanged) setResult(RESULT_OK);

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
        initializeDetectionSettings();

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

        savedShowNotDetected = AllergenPreferences.showNotDetected(this);
        showNotDetected = savedInstanceState == null ? savedShowNotDetected
                : savedInstanceState.getBoolean(DRAFT_SHOW_NOT_DETECTED, savedShowNotDetected);
        MaterialSwitch notDetectedSwitch = findViewById(R.id.show_not_detected_switch);
        notDetectedSwitch.setSaveEnabled(false);
        notDetectedSwitch.setChecked(showNotDetected);
        notDetectedSwitch.setOnCheckedChangeListener((button, checked) -> {
            showNotDetected = checked;
            updateUnsavedChanges();
        });
        // The whole row toggles, not only the small switch.
        ((View) notDetectedSwitch.getParent()).setOnClickListener(view -> notDetectedSwitch.toggle());

        savedMayContainRedWarning = AllergenPreferences.mayContainRedWarning(this);
        mayContainRedWarning = savedInstanceState == null ? savedMayContainRedWarning
                : savedInstanceState.getBoolean(DRAFT_MAY_CONTAIN_RED_WARNING, savedMayContainRedWarning);
        MaterialSwitch redWarningSwitch = findViewById(R.id.may_contain_red_warning_switch);
        redWarningSwitch.setSaveEnabled(false);
        redWarningSwitch.setChecked(mayContainRedWarning);
        redWarningSwitch.setOnCheckedChangeListener((button, checked) -> {
            mayContainRedWarning = checked;
            updateUnsavedChanges();
        });
        ((View) redWarningSwitch.getParent()).setOnClickListener(view -> redWarningSwitch.toggle());
        // updateSelectionSummary ran before these drafts were restored, so re-check them now.
        updateUnsavedChanges();

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
        if (cloudSettingsChanged() && !saveCloudConfiguration()) {
            ((MaterialButtonToggleGroup) findViewById(R.id.settings_section_toggle))
                    .check(R.id.section_model_button);
            return;
        }
        AllergenPreferences.saveSelectedAllergenIds(this, selectedAllergens);
        AllergenPreferences.saveShowNotDetected(this, showNotDetected);
        AllergenPreferences.saveMayContainRedWarning(this, mayContainRedWarning);
        setResult(RESULT_OK);
        finish();
    }

    private void initializeDetectionSettings() {
        apiKeyInput = findViewById(R.id.detection_api_key);
        openRouterModelInput = findViewById(R.id.detection_model_id);
        foodTypeModelInput = findViewById(R.id.food_type_model_id);
        decisionsModelInput = findViewById(R.id.detection_decisions_model_id);
        decisionsThresholdInput = findViewById(R.id.detection_threshold);
        cloudConcurrencyInput = findViewById(R.id.detection_concurrency);
        CloudDraft draft = new ViewModelProvider(this).get(CloudDraft.class);
        if (draft.apiKey == null) draft.apiKey = AnalysisPreferences.openRouterApiKey(this);
        apiKeyInput.setText(draft.apiKey);
        openRouterModelInput.setText(AnalysisPreferences.openRouterModel(this));
        foodTypeModelInput.setText(AnalysisPreferences.foodTypeModel(this));
        decisionsModelInput.setText(AnalysisPreferences.decisionsModel(this));
        decisionsThresholdInput.setText(formatThreshold(AnalysisPreferences.decisionsThreshold(this),
                getResources().getConfiguration().getLocales().get(0)));
        cloudConcurrencyInput.setText(formatConcurrency(AnalysisPreferences.cloudConcurrency(this)));
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                draft.apiKey = apiKeyInput.getText().toString();
                cloudJustSaved = false;
                renderCloudStatus();
                updateUnsavedChanges();
            }
        };
        apiKeyInput.addTextChangedListener(watcher);
        openRouterModelInput.addTextChangedListener(watcher);
        foodTypeModelInput.addTextChangedListener(watcher);
        decisionsModelInput.addTextChangedListener(watcher);
        decisionsThresholdInput.addTextChangedListener(watcher);
        cloudConcurrencyInput.addTextChangedListener(watcher);
        setModeLabel(R.id.detection_mode_matching, R.string.detection_matching,
                R.string.detection_matching_hint);
        setModeLabel(R.id.detection_mode_local_ai, R.string.detection_local_ai,
                R.string.detection_local_ai_hint);
        setModeLabel(R.id.detection_mode_openrouter, R.string.detection_openrouter,
                R.string.detection_openrouter_hint);
        setModeLabel(R.id.detection_mode_decisions, R.string.detection_decisions,
                R.string.detection_decisions_hint);
        renderDetectionMode();
        MaterialButtonToggleGroup location = findViewById(R.id.detection_location);
        location.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (checked && !renderingDetectionMode) {
                AnalysisPreferences.Mode previous = AnalysisPreferences.mode(this);
                boolean wasCloud = previous == AnalysisPreferences.Mode.OPENROUTER
                        || previous == AnalysisPreferences.Mode.DECISIONS;
                boolean cloud = checkedId == R.id.detection_location_cloud;
                if (cloud != wasCloud) {
                    selectDetectionMode(cloud ? AnalysisPreferences.Mode.OPENROUTER
                            : AnalysisPreferences.Mode.MATCHING);
                }
            }
        });
        ((RadioGroup) findViewById(R.id.detection_local_modes)).setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (!renderingDetectionMode) selectDetectionMode(
                            checkedId == R.id.detection_mode_local_ai
                                    ? AnalysisPreferences.Mode.LOCAL_AI : AnalysisPreferences.Mode.MATCHING);
                });
        ((RadioGroup) findViewById(R.id.detection_cloud_modes)).setOnCheckedChangeListener(
                (group, checkedId) -> {
                    if (!renderingDetectionMode) selectDetectionMode(
                            checkedId == R.id.detection_mode_decisions
                                    ? AnalysisPreferences.Mode.DECISIONS : AnalysisPreferences.Mode.OPENROUTER);
                });
        findViewById(R.id.detection_save_cloud).setOnClickListener(view -> saveCloudConfiguration());
    }

    private void selectDetectionMode(AnalysisPreferences.Mode mode) {
        AnalysisPreferences.saveMode(this, mode);
        analysisConfigurationChanged = true;
        setResult(RESULT_OK);
        renderDetectionMode();
    }

    private void renderDetectionMode() {
        AnalysisPreferences.Mode mode = AnalysisPreferences.mode(this);
        boolean cloud = mode == AnalysisPreferences.Mode.OPENROUTER
                || mode == AnalysisPreferences.Mode.DECISIONS;
        boolean decisions = mode == AnalysisPreferences.Mode.DECISIONS;
        renderingDetectionMode = true;
        ((MaterialButtonToggleGroup) findViewById(R.id.detection_location)).check(cloud
                ? R.id.detection_location_cloud : R.id.detection_location_local);
        ((RadioGroup) findViewById(R.id.detection_local_modes)).check(
                mode == AnalysisPreferences.Mode.LOCAL_AI
                        ? R.id.detection_mode_local_ai : R.id.detection_mode_matching);
        ((RadioGroup) findViewById(R.id.detection_cloud_modes)).check(decisions
                ? R.id.detection_mode_decisions : R.id.detection_mode_openrouter);
        renderingDetectionMode = false;
        findViewById(R.id.detection_local_modes).setVisibility(cloud ? View.GONE : View.VISIBLE);
        findViewById(R.id.detection_cloud_modes).setVisibility(cloud ? View.VISIBLE : View.GONE);
        findViewById(R.id.local_model_section).setVisibility(cloud ? View.GONE : View.VISIBLE);
        findViewById(R.id.local_model_title).setVisibility(cloud ? View.GONE : View.VISIBLE);
        findViewById(R.id.local_model_hint).setVisibility(cloud ? View.GONE : View.VISIBLE);
        findViewById(R.id.detection_cloud_configuration).setVisibility(View.VISIBLE);
        findViewById(R.id.detection_model_layout).setVisibility(cloud && !decisions ? View.VISIBLE : View.GONE);
        findViewById(R.id.detection_decisions_model_layout).setVisibility(decisions ? View.VISIBLE : View.GONE);
        findViewById(R.id.detection_threshold_layout).setVisibility(decisions ? View.VISIBLE : View.GONE);
        findViewById(R.id.detection_concurrency_layout).setVisibility(cloud ? View.VISIBLE : View.GONE);
        renderCloudStatus();
    }

    /** Bold-weight title over a smaller explanation, so each option explains itself. */
    private void setModeLabel(int buttonId, int titleRes, int hintRes) {
        String title = getString(titleRes);
        String hint = getString(hintRes);
        SpannableStringBuilder label = new SpannableStringBuilder(title).append('\n').append(hint);
        int start = title.length() + 1;
        label.setSpan(new TextAppearanceSpan(this, com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium), start, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(MaterialColors.getColor(this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant, 0)),
                start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((TextView) findViewById(buttonId)).setText(label);
    }

    /**
     * Confirms a save, or warns that the chosen cloud mode cannot run yet because nothing
     * usable is stored — mode changes apply at once, but cloud fields only once saved.
     */
    private void renderCloudStatus() {
        TextView status = findViewById(R.id.detection_cloud_save_status);
        if (status == null || apiKeyInput == null) return;
        AnalysisPreferences.Mode mode = AnalysisPreferences.mode(this);
        String savedModel = mode == AnalysisPreferences.Mode.DECISIONS
                ? AnalysisPreferences.decisionsModel(this) : AnalysisPreferences.openRouterModel(this);
        boolean cloud = mode == AnalysisPreferences.Mode.OPENROUTER
                || mode == AnalysisPreferences.Mode.DECISIONS;
        boolean unusable = cloud && (AnalysisPreferences.openRouterApiKey(this).isEmpty()
                || !validModelId(savedModel));
        if (unusable) {
            showStatus(status, R.string.detection_cloud_needs_key, R.drawable.ic_info_small,
                    androidx.appcompat.R.attr.colorError);
        } else if (cloudJustSaved) {
            showStatus(status, R.string.detection_cloud_saved, R.drawable.ic_check,
                    androidx.appcompat.R.attr.colorPrimary);
        } else {
            status.setVisibility(View.GONE);
        }
    }

    private void showStatus(TextView status, int textRes, int iconRes, int colorAttr) {
        int color = MaterialColors.getColor(status, colorAttr);
        status.setText(textRes);
        status.setTextColor(color);
        Drawable icon = AppCompatResources.getDrawable(this, iconRes).mutate();
        int size = Math.round(18 * getResources().getDisplayMetrics().density);
        icon.setBounds(0, 0, size, size);
        icon.setTint(color);
        status.setCompoundDrawablesRelative(icon, null, null, null);
        status.setVisibility(View.VISIBLE);
    }

    private boolean saveCloudConfiguration() {
        AnalysisPreferences.Mode mode = AnalysisPreferences.mode(this);
        boolean cloud = mode == AnalysisPreferences.Mode.OPENROUTER
                || mode == AnalysisPreferences.Mode.DECISIONS;
        String key = input(apiKeyInput);
        String llmModel = input(openRouterModelInput);
        String foodTypeModel = input(foodTypeModelInput);
        String decisionsModel = input(decisionsModelInput);
        TextInputLayout keyLayout = findViewById(R.id.detection_api_key_layout);
        TextInputLayout llmLayout = findViewById(R.id.detection_model_layout);
        TextInputLayout foodTypeLayout = findViewById(R.id.food_type_model_layout);
        TextInputLayout decisionsLayout = findViewById(R.id.detection_decisions_model_layout);
        TextInputLayout thresholdLayout = findViewById(R.id.detection_threshold_layout);
        TextInputLayout concurrencyLayout = findViewById(R.id.detection_concurrency_layout);
        keyLayout.setError(null);
        llmLayout.setError(null);
        foodTypeLayout.setError(null);
        decisionsLayout.setError(null);
        thresholdLayout.setError(null);
        concurrencyLayout.setError(null);
        int concurrency;
        try {
            concurrency = parseConcurrency(input(cloudConcurrencyInput));
        } catch (NumberFormatException exception) {
            concurrencyLayout.setError(getString(R.string.detection_concurrency_invalid));
            cloudConcurrencyInput.requestFocus();
            return false;
        }
        if ((cloud || !foodTypeModel.isEmpty()) && (key.isEmpty() || key.matches(".*\\s+.*"))) {
            keyLayout.setError(getString(R.string.detection_key_required));
            apiKeyInput.requestFocus();
            return false;
        }
        if (mode == AnalysisPreferences.Mode.OPENROUTER && !validModelId(llmModel)) {
            llmLayout.setError(getString(R.string.detection_model_required));
            openRouterModelInput.requestFocus();
            return false;
        }
        if (!foodTypeModel.isEmpty() && !validModelId(foodTypeModel)) {
            foodTypeLayout.setError(getString(R.string.detection_model_required));
            foodTypeModelInput.requestFocus();
            return false;
        }
        if (mode == AnalysisPreferences.Mode.DECISIONS && !validModelId(decisionsModel)) {
            decisionsLayout.setError(getString(R.string.detection_model_required));
            decisionsModelInput.requestFocus();
            return false;
        }
        double threshold = AnalysisPreferences.decisionsThreshold(this);
        try {
            threshold = parseThreshold(input(decisionsThresholdInput),
                    getResources().getConfiguration().getLocales().get(0));
        } catch (NumberFormatException exception) {
            if (mode == AnalysisPreferences.Mode.DECISIONS) {
                thresholdLayout.setError(getString(R.string.detection_threshold_invalid));
                decisionsThresholdInput.requestFocus();
                return false;
            }
            decisionsThresholdInput.setText(formatThreshold(threshold,
                    getResources().getConfiguration().getLocales().get(0)));
        }
        AnalysisPreferences.saveOpenRouterApiKey(this, key);
        AnalysisPreferences.saveOpenRouterModel(this, llmModel);
        AnalysisPreferences.saveFoodTypeModel(this, foodTypeModel);
        AnalysisPreferences.saveDecisionsModel(this, decisionsModel);
        AnalysisPreferences.saveDecisionsThreshold(this, threshold);
        AnalysisPreferences.saveCloudConcurrency(this, concurrency);
        cloudConcurrencyInput.setText(formatConcurrency(concurrency));
        analysisConfigurationChanged = true;
        setResult(RESULT_OK);
        cloudJustSaved = true;
        renderCloudStatus();
        updateUnsavedChanges();
        return true;
    }

    private static boolean validModelId(String value) {
        return value.matches("[^\\s/]+/[^\\s]+");
    }

    private String formatConcurrency(int concurrency) {
        NumberFormat format = NumberFormat.getIntegerInstance(
                getResources().getConfiguration().getLocales().get(0));
        format.setGroupingUsed(false);
        return format.format(concurrency);
    }

    static int parseConcurrency(String text) {
        int concurrency = Integer.parseInt(text);
        if (concurrency < AnalysisPreferences.MIN_CLOUD_CONCURRENCY) {
            throw new NumberFormatException("Cloud concurrency must be positive");
        }
        return concurrency;
    }

    private static String input(EditText field) {
        return field.getText().toString().trim();
    }

    private static NumberFormat thresholdNumberFormat(Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMaximumFractionDigits(340);
        return format;
    }

    static String formatThreshold(double threshold, Locale locale) {
        return thresholdNumberFormat(locale).format(threshold);
    }

    static double parseThreshold(String text, Locale locale) {
        ParsePosition position = new ParsePosition(0);
        Number number = thresholdNumberFormat(locale).parse(text, position);
        if (number == null || position.getIndex() != text.length()) {
            throw new NumberFormatException("Enter a complete number");
        }
        double threshold = number.doubleValue();
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new NumberFormatException("Threshold must be between 0 and 1");
        }
        return threshold;
    }

    private boolean cloudSettingsChanged() {
        return apiKeyInput != null && (!input(apiKeyInput).equals(AnalysisPreferences.openRouterApiKey(this))
                || !input(openRouterModelInput).equals(AnalysisPreferences.openRouterModel(this))
                || !input(foodTypeModelInput).equals(AnalysisPreferences.foodTypeModel(this))
                || !input(decisionsModelInput).equals(AnalysisPreferences.decisionsModel(this))
                || !input(cloudConcurrencyInput).equals(
                        formatConcurrency(AnalysisPreferences.cloudConcurrency(this)))
                || !input(decisionsThresholdInput).equals(
                        formatThreshold(AnalysisPreferences.decisionsThreshold(this),
                                getResources().getConfiguration().getLocales().get(0))));
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
        updateUnsavedChanges();
    }

    private void updateUnsavedChanges() {
        unsavedChangesCallback.setEnabled(!selectedAllergens.equals(savedAllergens)
                || showNotDetected != savedShowNotDetected
                || mayContainRedWarning != savedMayContainRedWarning || cloudSettingsChanged());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(ANALYSIS_CONFIGURATION_CHANGED, analysisConfigurationChanged);
        outState.putStringArrayList(DRAFT_ALLERGENS, new ArrayList<>(selectedAllergens));
        outState.putBoolean(DRAFT_SHOW_NOT_DETECTED, showNotDetected);
        outState.putBoolean(DRAFT_MAY_CONTAIN_RED_WARNING, mayContainRedWarning);
        outState.putBoolean(MODEL_DETAILS_EXPANDED, modelDetails.getVisibility() == View.VISIBLE);
        outState.putBoolean(MODEL_SECTION_SHOWN, modelSection.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(outState);
    }
}
