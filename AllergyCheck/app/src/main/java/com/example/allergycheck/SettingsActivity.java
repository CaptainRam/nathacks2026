package com.example.allergycheck;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
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
import androidx.lifecycle.Lifecycle;

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
    private static final String ANALYSIS_CONFIGURATION_CHANGED = "analysis_configuration_changed";
    static final String EXTRA_ANALYSIS_CONFIGURATION_CHANGED = ANALYSIS_CONFIGURATION_CHANGED;
    static final String EXTRA_AI_PROVIDER_CHANGED = "ai_provider_changed";
    private static final String INITIAL_ANALYSIS_SIGNATURE = "initial_analysis_signature";
    private static final String INITIAL_PROVIDER_SIGNATURE = "initial_provider_signature";
    private static final String MODEL_NAVIGATION_GENERATION = "model_navigation_generation";

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
    private MaterialButton modelActionButton;
    private View modelDetails;
    private View modelTerms;
    private MaterialButton modelDetailsButton;
    private EditText apiKeyInput;
    private EditText openRouterModelInput;
    private EditText decisionsModelInput;
    private EditText decisionsThresholdInput;
    private EditText cloudConcurrencyInput;
    private boolean renderingDetectionMode;
    /** True from a successful cloud save until the next edit. */
    private boolean cloudJustSaved;
    private boolean analysisConfigurationChanged;
    private String initialAnalysisSignature;
    private String initialProviderSignature;
    private ModelReturnFocus modelReturnFocus;
    private Boolean modelSectionShown;

    /** Keeps the unsaved key through rotation without putting credentials into saved state. */
    public static final class CloudDraft extends ViewModel {
        String apiKey;
    }

    /** Retains the dialog's navigation origin; the screen reader owns the returning focus. */
    public static final class ModelReturnFocus extends ViewModel {
        boolean initialized;
        int navigationGeneration;
        int targetId = View.NO_ID;
        int targetNavigationGeneration;
        long deadline;
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

    /** Retains the original model action while the confirmation is recreated on rotation. */
    public static class DeleteModelDialog extends DialogFragment {
        static final String TAG = "delete_model_dialog";
        private static final String GENERATION = "navigation_generation";

        static DeleteModelDialog of(int generation) {
            DeleteModelDialog dialog = new DeleteModelDialog();
            Bundle arguments = new Bundle();
            arguments.putInt(GENERATION, generation);
            dialog.setArguments(arguments);
            return dialog;
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            SettingsActivity activity = (SettingsActivity) requireActivity();
            activity.armModelReturnHint(requireArguments().getInt(GENERATION), Long.MAX_VALUE);
            return new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.model_settings_delete_title)
                    .setMessage(R.string.model_settings_delete_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.model_settings_delete, (dialog, which) ->
                            activity.deleteConfirmedModel(requireArguments().getInt(GENERATION)))
                    .create();
        }

        @Override
        public void onDismiss(@NonNull DialogInterface dialog) {
            super.onDismiss(dialog);
            if (getActivity() instanceof SettingsActivity) {
                SettingsActivity activity = (SettingsActivity) getActivity();
                if (!activity.isChangingConfigurations()) {
                    activity.retainModelReturnHint(requireArguments().getInt(GENERATION));
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modelReturnFocus = new ViewModelProvider(this).get(ModelReturnFocus.class);
        if (!modelReturnFocus.initialized) {
            modelReturnFocus.initialized = true;
            modelReturnFocus.navigationGeneration = savedInstanceState == null ? 0
                    : savedInstanceState.getInt(MODEL_NAVIGATION_GENERATION);
        }
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_settings);
        initialAnalysisSignature = savedInstanceState == null ? configurationSignature(false)
                : savedInstanceState.getString(INITIAL_ANALYSIS_SIGNATURE, configurationSignature(false));
        initialProviderSignature = savedInstanceState == null ? configurationSignature(true)
                : savedInstanceState.getString(INITIAL_PROVIDER_SIGNATURE, configurationSignature(true));
        analysisConfigurationChanged = !initialAnalysisSignature.equals(configurationSignature(false));
        if (analysisConfigurationChanged) setSavedResult();

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
        arrangeSaveBar(findViewById(R.id.settings));

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
        // Keep this same View and ID when Delete becomes Download. TalkBack's window history
        // can then restore the original node instead of searching for a removed control.
        modelActionButton = findViewById(R.id.model_download_button);
        InitialFocusHint.install(modelActionButton, this::hasModelReturnHint);
        observeModelReadingPosition();
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
        modelActionButton.setOnClickListener(view -> {
            if (!modelManager.getStatus().isInstalled()) {
                clearModelReturnHint(false);
                modelManager.download();
                return;
            }
            FragmentManager fragments = getSupportFragmentManager();
            if (!fragments.isStateSaved()
                    && fragments.findFragmentByTag(DeleteModelDialog.TAG) == null) {
                clearModelReturnHint(false);
                DeleteModelDialog.of(modelReturnFocus.navigationGeneration)
                        .showNow(fragments, DeleteModelDialog.TAG);
            }
        });
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
        // updateSelectionSummary ran before this draft was restored, so re-check it now.
        updateUnsavedChanges();
        notDetectedSwitch.setOnCheckedChangeListener((button, checked) -> {
            showNotDetected = checked;
            updateUnsavedChanges();
        });
        // The whole row toggles, not only the small switch.
        ((View) notDetectedSwitch.getParent()).setOnClickListener(view -> notDetectedSwitch.toggle());

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
    protected void onPause() {
        // A translucent foreground Activity may pause us without ever reaching onStop.
        // Keep the dialog's origin, but never revive its old return request after resuming.
        clearModelReturnHint(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        modelManager.removeListener(modelListener);
        super.onStop();
    }

    /** Shows one section; the save bar belongs to allergens because model actions apply at once. */
    private void showSection(boolean model) {
        if (modelSectionShown != null && modelSectionShown != model) {
            clearModelReturnHint(true);
        }
        modelSectionShown = model;
        allergenSection.setVisibility(model ? View.GONE : View.VISIBLE);
        modelSection.setVisibility(model ? View.VISIBLE : View.GONE);
        saveBar.setVisibility(model ? View.GONE : View.VISIBLE);
        scroll.post(this::updateHeaderDivider);
    }

    private void updateHeaderDivider() {
        headerDivider.setVisibility(scroll.canScrollVertically(-1) ? View.VISIBLE : View.INVISIBLE);
    }

    /** Keep the content usable in short windows instead of reserving most of it for fixed bars. */
    static void arrangeSaveBar(@NonNull View root) {
        if (root.getResources().getConfiguration().screenHeightDp >= 400) return;
        View bar = root.findViewById(R.id.settings_save_bar);
        ViewGroup content = root.findViewById(R.id.allergen_section);
        ViewGroup parent = (ViewGroup) bar.getParent();
        if (parent != content) {
            parent.removeView(bar);
            content.addView(bar);
        }
    }

    private void savePreferences() {
        if (cloudSettingsChanged() && !saveCloudConfiguration()) {
            ((MaterialButtonToggleGroup) findViewById(R.id.settings_section_toggle))
                    .check(R.id.section_model_button);
            return;
        }
        AllergenPreferences.saveSelectedAllergenIds(this, selectedAllergens);
        AllergenPreferences.saveShowNotDetected(this, showNotDetected);
        setSavedResult();
        finish();
    }

    private void initializeDetectionSettings() {
        apiKeyInput = findViewById(R.id.detection_api_key);
        openRouterModelInput = findViewById(R.id.detection_model_id);
        decisionsModelInput = findViewById(R.id.detection_decisions_model_id);
        decisionsThresholdInput = findViewById(R.id.detection_threshold);
        cloudConcurrencyInput = findViewById(R.id.detection_concurrency);
        CloudDraft draft = new ViewModelProvider(this).get(CloudDraft.class);
        if (draft.apiKey == null) draft.apiKey = AnalysisPreferences.openRouterApiKey(this);
        apiKeyInput.setText(draft.apiKey);
        openRouterModelInput.setText(AnalysisPreferences.openRouterModel(this));
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
        clearModelReturnHint(true);
        AnalysisPreferences.saveMode(this, mode);
        setSavedResult();
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
        findViewById(R.id.detection_cloud_configuration).setVisibility(cloud ? View.VISIBLE : View.GONE);
        findViewById(R.id.detection_model_layout).setVisibility(decisions ? View.GONE : View.VISIBLE);
        findViewById(R.id.detection_decisions_model_layout).setVisibility(decisions ? View.VISIBLE : View.GONE);
        findViewById(R.id.detection_threshold_layout).setVisibility(decisions ? View.VISIBLE : View.GONE);
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
        boolean missingKey = cloud && AnalysisPreferences.openRouterApiKey(this).isEmpty();
        boolean missingModel = cloud && !validModelId(savedModel);
        if (missingKey) {
            showStatus(status, R.string.detection_cloud_needs_key, R.drawable.ic_info_small,
                    androidx.appcompat.R.attr.colorError);
        } else if (missingModel) {
            showStatus(status, R.string.detection_cloud_needs_model, R.drawable.ic_info_small,
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
        setTextIfChanged(status, getText(textRes));
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
        String decisionsModel = input(decisionsModelInput);
        TextInputLayout keyLayout = findViewById(R.id.detection_api_key_layout);
        TextInputLayout llmLayout = findViewById(R.id.detection_model_layout);
        TextInputLayout decisionsLayout = findViewById(R.id.detection_decisions_model_layout);
        TextInputLayout thresholdLayout = findViewById(R.id.detection_threshold_layout);
        TextInputLayout concurrencyLayout = findViewById(R.id.detection_concurrency_layout);
        keyLayout.setError(null);
        llmLayout.setError(null);
        decisionsLayout.setError(null);
        thresholdLayout.setError(null);
        concurrencyLayout.setError(null);
        int concurrency = AnalysisPreferences.cloudConcurrency(this);
        try {
            concurrency = parseConcurrency(input(cloudConcurrencyInput));
        } catch (NumberFormatException exception) {
            if (cloud) {
                concurrencyLayout.setError(getString(R.string.detection_concurrency_invalid));
                cloudConcurrencyInput.requestFocus();
                return false;
            }
            // Local modes do not use this hidden draft. Keep the last usable saved value,
            // just as an invalid inactive Decisions threshold keeps its saved value below.
            cloudConcurrencyInput.setText(formatConcurrency(concurrency));
        }
        if (cloud && (key.isEmpty() || key.matches(".*\\s+.*"))) {
            keyLayout.setError(getString(R.string.detection_key_required));
            apiKeyInput.requestFocus();
            return false;
        }
        if (mode == AnalysisPreferences.Mode.OPENROUTER && !validModelId(llmModel)) {
            llmLayout.setError(getString(R.string.detection_model_required));
            openRouterModelInput.requestFocus();
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
        AnalysisPreferences.saveDecisionsModel(this, decisionsModel);
        AnalysisPreferences.saveDecisionsThreshold(this, threshold);
        AnalysisPreferences.saveCloudConcurrency(this, concurrency);
        cloudConcurrencyInput.setText(formatConcurrency(concurrency));
        setSavedResult();
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

    private void setSavedResult() {
        analysisConfigurationChanged = !initialAnalysisSignature.equals(configurationSignature(false));
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ANALYSIS_CONFIGURATION_CHANGED,
                analysisConfigurationChanged).putExtra(EXTRA_AI_PROVIDER_CHANGED,
                !initialProviderSignature.equals(configurationSignature(true))));
    }

    /** Save only a one-way comparison token across rotation, never the API key itself. */
    private String configurationSignature(boolean providerOnly) {
        AnalysisPreferences.Mode mode = AnalysisPreferences.mode(this);
        StringBuilder value = new StringBuilder(mode.name());
        if (mode == AnalysisPreferences.Mode.OPENROUTER || mode == AnalysisPreferences.Mode.DECISIONS) {
            appendSignatureValue(value, AnalysisPreferences.openRouterApiKey(this));
            appendSignatureValue(value, mode == AnalysisPreferences.Mode.DECISIONS
                    ? AnalysisPreferences.decisionsModel(this) : AnalysisPreferences.openRouterModel(this));
            if (!providerOnly) {
                appendSignatureValue(value, Integer.toString(AnalysisPreferences.cloudConcurrency(this)));
                if (mode == AnalysisPreferences.Mode.DECISIONS) {
                    appendSignatureValue(value, Double.toString(AnalysisPreferences.decisionsThreshold(this)));
                }
            }
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                    value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                hex.append(Character.forDigit((part >>> 4) & 15, 16));
                hex.append(Character.forDigit(part & 15, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void appendSignatureValue(StringBuilder signature, String value) {
        signature.append(':').append(value.length()).append(':').append(value);
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
        setTextIfChanged(modelAvailability, getText(badgeText(status, installed)));
        modelAvailability.setBackgroundTintList(failed ? pillFailedBackground : pillBackground);
        modelAvailability.setTextColor(failed ? pillFailedText : pillText);
        modelActionButton.setEnabled(!downloading && !installing);
        modelActionButton.setText(installed ? R.string.model_settings_delete
                : failed ? R.string.model_settings_retry : R.string.model_settings_download);
        modelActionButton.setContentDescription(getText(installed ? R.string.a11y_model_delete
                : failed ? R.string.a11y_model_retry : R.string.a11y_model_download));
        // The terms ask to be read before downloading, so they only matter until it is installed.
        modelTerms.setVisibility(installed ? View.GONE : View.VISIBLE);
        // "Installed" already says it all; the line returns for progress, errors and hints.
        modelStatus.setVisibility(status.state == LocalModelManager.State.READY
                ? View.GONE : View.VISIBLE);
        // The badge announces state changes. Byte counts update every second, so leave those
        // available to explore without repeatedly interrupting TalkBack's current reading.
        modelStatus.setAccessibilityLiveRegion(status.state == LocalModelManager.State.DOWNLOADING
                ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);

        if (downloading || installing) {
            boolean indeterminate = installing || status.totalBytes <= 0;
            if (modelProgress.isIndeterminate() != indeterminate) {
                modelProgress.hide();
                modelProgress.setIndeterminate(indeterminate);
            }
            if (!installing && status.totalBytes > 0) {
                int percent = (int) Math.max(0, Math.min(100,
                        Math.round(100d * status.downloadedBytes / status.totalBytes)));
                modelProgress.setProgressCompat(percent, false);
            }
            modelProgress.setVisibility(View.VISIBLE);
        } else {
            modelProgress.setVisibility(View.GONE);
        }

        switch (status.state) {
            case READY:
                setTextIfChanged(modelStatus, getText(R.string.model_settings_ready));
                break;
            case DOWNLOADING:
                setTextIfChanged(modelStatus, downloadProgress(status));
                break;
            case PAUSED:
                setTextIfChanged(modelStatus, status.error == null
                        ? getString(R.string.model_settings_waiting, downloadProgress(status)) : status.error);
                break;
            case INSTALLING:
                setTextIfChanged(modelStatus, getText(R.string.model_settings_installing));
                break;
            case FAILED:
                setTextIfChanged(modelStatus, status.error == null || status.error.trim().isEmpty()
                        ? getString(R.string.model_settings_failed)
                        : getString(R.string.model_settings_failed_reason, status.error));
                break;
            case MISSING:
            default:
                setTextIfChanged(modelStatus, getText(R.string.model_settings_not_downloaded));
                break;
        }
    }

    private boolean isModelActionPage(int generation) {
        return modelReturnFocus.navigationGeneration == generation && !isFinishing()
                && !isDestroyed() && modelSection.getVisibility() == View.VISIBLE
                && findViewById(R.id.local_model_section).getVisibility() == View.VISIBLE;
    }

    private void deleteConfirmedModel(int generation) {
        if (!isModelActionPage(generation)
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            return;
        }
        modelManager.delete();
        // Bind the replacement action before the dialog gives its window back to this Activity.
        renderModelStatus(modelManager.getStatus());
    }

    private void retainModelReturnHint(int generation) {
        if (!isModelActionPage(generation)
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
        armModelReturnHint(generation, SystemClock.uptimeMillis() + 1500);
    }

    private void armModelReturnHint(int generation, long deadline) {
        if (!isModelActionPage(generation)) return;
        modelReturnFocus.targetId = R.id.model_download_button;
        modelReturnFocus.targetNavigationGeneration = generation;
        modelReturnFocus.deadline = deadline;
    }

    private boolean hasModelReturnHint() {
        return modelReturnFocus.targetId == R.id.model_download_button
                && isModelActionPage(modelReturnFocus.targetNavigationGeneration)
                && SystemClock.uptimeMillis() < modelReturnFocus.deadline
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && modelActionButton.isShown() && modelActionButton.isEnabled();
    }

    private void clearModelReturnHint(boolean navigationChanged) {
        if (navigationChanged) ++modelReturnFocus.navigationGeneration;
        modelReturnFocus.targetId = View.NO_ID;
    }

    private void observeModelReadingPosition() {
        View root = findViewById(R.id.settings);
        root.setAccessibilityDelegate(new InitialFocusHint.Delegate(root) {
            @Override
            public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                                                           AccessibilityEvent event) {
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                    // The service has selected its own reading position, whether the returned
                    // action or another control. Consume the fallback without moving that focus.
                    clearModelReturnHint(false);
                }
                return super.onRequestSendAccessibilityEvent(host, child, event);
            }
        });
    }

    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
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
        ViewCompat.setStateDescription(modelDetailsButton, getText(expanded
                ? R.string.a11y_settings_expanded : R.string.a11y_settings_collapsed));
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
                || showNotDetected != savedShowNotDetected || cloudSettingsChanged());
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(MODEL_NAVIGATION_GENERATION, modelReturnFocus.navigationGeneration);
        outState.putBoolean(ANALYSIS_CONFIGURATION_CHANGED, analysisConfigurationChanged);
        outState.putString(INITIAL_ANALYSIS_SIGNATURE, initialAnalysisSignature);
        outState.putString(INITIAL_PROVIDER_SIGNATURE, initialProviderSignature);
        outState.putStringArrayList(DRAFT_ALLERGENS, new ArrayList<>(selectedAllergens));
        outState.putBoolean(DRAFT_SHOW_NOT_DETECTED, showNotDetected);
        outState.putBoolean(MODEL_DETAILS_EXPANDED, modelDetails.getVisibility() == View.VISIBLE);
        outState.putBoolean(MODEL_SECTION_SHOWN, modelSection.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(outState);
    }
}
