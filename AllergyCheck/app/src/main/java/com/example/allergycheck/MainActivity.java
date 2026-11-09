package com.example.allergycheck;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.FragmentManager;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AiAnalyzer;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.history.ScanHistory;
import com.example.allergycheck.ingredients.IngredientExtractor;
import com.example.allergycheck.ingredients.LocalModelManager;
import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements HistoryHost {
    private static final String PHOTO_STATE = "selected_photo";
    private static final String PENDING_PHOTO_STATE = "pending_photo";
    private static final String CAMERA_STATE = "pending_camera_photo";
    private static final String ANALYSIS_STATE = "analysis_mode";
    private static final String SETTINGS_OPEN_STATE = "settings_open";
    private static final String SETTINGS_INTERRUPTED_STATE = "settings_interrupted_scan";
    private static final String HISTORY_STATE = "history_visible";
    private static final String HISTORY_FRAGMENT = "history";
    private static final int MAX_PREVIEW_SIZE = 1600;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private Uri selectedPhoto;
    private Uri pendingPhoto;
    private Uri pendingCameraPhoto;
    private int imageRequest;
    private View loadingOverlay;
    private ImageView photoPreview;
    private MaterialButton importButton;
    private MaterialButton cameraButton;
    private MaterialButton replaceButton;
    private MaterialButton startAnalysisButton;
    private MaterialButton allergensButton;
    private MaterialButton resultSecondaryButton;
    private ChipGroup allergenChips;
    private ViewGroup root;
    private ViewGroup contentColumn;
    private View contentScroll;
    private View introGroup;
    private View photoCard;
    private View photoInputRow;
    private View frontPhotoCard;
    private View frontPhotoHint;
    private View previewActions;
    private View tipsGroup;
    private View photoStripScroll;
    private LinearLayout photoStrip;
    private ViewGroup analysisGroup;
    private View analysisLoading;
    private ScanProgress scanProgress;
    private ViewGroup analysisResult;
    private View restartButton;
    private AnalysisResultView resultView;
    private OcrPipeline ocrPipeline;
    private AiAnalysisRunner aiRunner;
    private FoodCrossCheckController foodCrossCheck;
    private boolean settingsOpen;
    private boolean settingsInterruptedScan;
    /** True while the page shows a check (in progress or finished) instead of the home. */
    private boolean analysisMode;
    private boolean historyVisible;
    private BottomNavigationView bottomNav;
    private View historyContainer;
    private ResultAction resultAction = ResultAction.NONE;
    private final List<ValueAnimator> scanAnimators = new ArrayList<>();
    private PopupMenu changePhotoMenu;
    private HomeMotion.Flight photoFlight;
    private ModelDownloadController modelDownloadController;

    /** The extra button under a result, when the result needs one. */
    private enum ResultAction { NONE, RETRY, CHOOSE_ALLERGENS, CONFIGURE_AI }

    /** Back during a check returns to the photo instead of leaving the app. */
    private final OnBackPressedCallback analysisBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            cancelAnalysis();
        }
    };

    /**
     * Back on the history tab closes an open scan detail first, then returns to the scan page
     * rather than leaving the app. The back stack is popped here explicitly rather than left to
     * the FragmentManager's own callback: this one is registered later, so it would otherwise win
     * and swallow the detail's back.
     */
    private final OnBackPressedCallback historyBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStack();
                return;
            }
            showScanTab();
        }
    };

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                boolean resume = settingsInterruptedScan;
                settingsOpen = false;
                settingsInterruptedScan = false;
                if (result.getResultCode() == RESULT_OK) {
                    onAllergenPreferencesSaved();
                } else if (resume && analysisMode) {
                    rescanCurrentPhoto();
                }
                foodCrossCheck.refreshConfiguration();
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    loadPhoto(uri);
                }
            });

    private final ActivityResultLauncher<Intent> photoCapture =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri capturedPhoto = pendingCameraPhoto;
                pendingCameraPhoto = null;
                if (result.getResultCode() == RESULT_OK && capturedPhoto != null) {
                    loadPhoto(capturedPhoto);
                    return;
                }
                // Cancelled, or a capture that never produced a file. The placeholder goes, and the
                // camera button is handed back unconditionally: the previous version returned early
                // when there was no pending URI, which left the button disabled with nothing able to
                // re-enable it, so backing out of the camera locked the camera out for the rest of
                // the session.
                if (capturedPhoto != null) {
                    deleteCameraPhoto(capturedPhoto);
                }
                updateCameraButton();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsOpen = savedInstanceState != null && savedInstanceState.getBoolean(SETTINGS_OPEN_STATE);
        settingsInterruptedScan = savedInstanceState != null
                && savedInstanceState.getBoolean(SETTINGS_INTERRUPTED_STATE);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        boolean darkTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat systemBars = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        systemBars.setAppearanceLightStatusBars(!darkTheme);
        systemBars.setAppearanceLightNavigationBars(!darkTheme);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(systemBarInsets.left, systemBarInsets.top,
                    systemBarInsets.right, systemBarInsets.bottom);
            return insets;
        });

        loadingOverlay = findViewById(R.id.loading_overlay);
        photoPreview = findViewById(R.id.photo_preview);
        importButton = findViewById(R.id.import_button);
        cameraButton = findViewById(R.id.camera_button);
        replaceButton = findViewById(R.id.replace_button);
        startAnalysisButton = findViewById(R.id.start_analysis_button);
        allergensButton = findViewById(R.id.allergens_button);
        resultSecondaryButton = findViewById(R.id.result_secondary_button);
        allergenChips = findViewById(R.id.allergen_chips);
        root = findViewById(R.id.main);
        contentColumn = findViewById(R.id.content_column);
        contentScroll = findViewById(R.id.content_scroll);
        introGroup = findViewById(R.id.intro_group);
        photoCard = findViewById(R.id.photo_card);
        photoInputRow = findViewById(R.id.photo_input_row);
        frontPhotoCard = findViewById(R.id.front_photo_card);
        frontPhotoHint = findViewById(R.id.front_photo_hint);
        previewActions = findViewById(R.id.preview_actions);
        tipsGroup = findViewById(R.id.tips_group);
        photoStripScroll = findViewById(R.id.photo_strip_scroll);
        photoStrip = findViewById(R.id.photo_strip);
        analysisGroup = findViewById(R.id.analysis_group);
        analysisLoading = findViewById(R.id.analysis_loading);
        scanProgress = new ScanProgress(findViewById(R.id.analysis_progress));
        analysisResult = findViewById(R.id.analysis_result);
        restartButton = findViewById(R.id.restart_button);
        resultView = new AnalysisResultView(analysisResult);
        ocrPipeline = new OcrPipeline();
        aiRunner = new AiAnalysisRunner(this);
        foodCrossCheck = new FoodCrossCheckController(this, savedInstanceState,
                () -> openSettings(true));
        foodCrossCheck.setOnPhotoChangedListener(this::onFrontPhotoChanged);
        updatePhotoLayout();
        getOnBackPressedDispatcher().addCallback(this, analysisBackCallback);
        // After the analysis callback, so it wins while history is showing.
        getOnBackPressedDispatcher().addCallback(this, historyBackCallback);
        modelDownloadController = new ModelDownloadController(this, () -> openSettings(true),
                () -> AnalysisPreferences.mode(this) == AnalysisPreferences.Mode.LOCAL_AI);
        // Prune anything past the retention window and sweep images with no row. Cheap from here:
        // it only hands the work to the history worker.
        ScanHistory.get(this).maintain(null);
        bottomNav = findViewById(R.id.bottom_nav);
        historyContainer = findViewById(R.id.history_container);
        bottomNav.setOnItemSelectedListener(item -> {
            showHistory(item.getItemId() == R.id.tab_history);
            return true;
        });
        // Set explicitly rather than relying on the menu order, so the default is unambiguous.
        boolean restoreHistory = savedInstanceState != null
                && savedInstanceState.getBoolean(HISTORY_STATE);
        bottomNav.setSelectedItemId(restoreHistory ? R.id.tab_history : R.id.tab_scan);

        getSupportFragmentManager().setFragmentResultListener(
                AllergenPreferencesDialogFragment.RESULT_KEY, this, (key, result) ->
                        findViewById(R.id.main).post(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (result.getBoolean(AllergenPreferencesDialogFragment.SAVED)) {
                                onAllergenPreferencesSaved();
                            }
                        }));

        importButton.setOnClickListener(view -> openPhotoPicker());
        cameraButton.setOnClickListener(view -> takePhoto());
        replaceButton.setOnClickListener(view -> openPhotoPicker());
        photoPreview.setOnClickListener(view -> showChangePhotoMenu(view, 0));
        startAnalysisButton.setOnClickListener(view -> startAnalysis());
        findViewById(R.id.cancel_analysis_button).setOnClickListener(view -> cancelAnalysis());
        restartButton.setOnClickListener(view -> restart());
        resultSecondaryButton.setOnClickListener(view -> onResultAction());
        findViewById(R.id.remove_button).setOnClickListener(view -> removePhoto());
        findViewById(R.id.settings_button).setOnClickListener(view -> openSettings());
        allergensButton.setOnClickListener(view -> openAllergenPreferences());
        updateAllergenSummary();

        if (savedInstanceState != null) {
            String cameraPhoto = savedInstanceState.getString(CAMERA_STATE);
            if (cameraPhoto != null) {
                pendingCameraPhoto = Uri.parse(cameraPhoto);
                cameraButton.setEnabled(false);
            }
        }

        String savedPhoto = savedInstanceState != null
                ? savedInstanceState.getString(PHOTO_STATE)
                : preferences().getString(PHOTO_STATE, null);
        if (savedPhoto != null) {
            selectedPhoto = Uri.parse(savedPhoto);
        }
        // A check that was on screen before recreation is run again once its photo is decoded.
        if (savedInstanceState != null && savedInstanceState.getBoolean(ANALYSIS_STATE)
                && selectedPhoto != null) {
            setAnalysisMode(true);
            showAnalysisLoading();
        }
        String savedPendingPhoto = savedInstanceState == null ? null
                : savedInstanceState.getString(PENDING_PHOTO_STATE);
        if (savedPendingPhoto != null) {
            loadPhoto(Uri.parse(savedPendingPhoto));
        } else if (selectedPhoto != null) {
            loadPhoto(selectedPhoto);
        }
    }

    private void openPhotoPicker() {
        try {
            photoPicker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } catch (ActivityNotFoundException exception) {
            Snackbar.make(findViewById(R.id.main), R.string.picker_error,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    private void takePhoto() {
        if (pendingCameraPhoto != null) {
            return;
        }
        File photoFile = null;
        try {
            File photoDirectory = new File(getFilesDir(), "photos");
            if (!photoDirectory.isDirectory() && !photoDirectory.mkdirs()) {
                throw new IOException("Unable to create photo directory");
            }
            photoFile = File.createTempFile("capture_", ".jpg", photoDirectory);
            pendingCameraPhoto = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photoFile);
            cameraButton.setEnabled(false);
            Intent captureIntent = new Intent(this, CameraCaptureActivity.class);
            captureIntent.putExtra(CameraCaptureActivity.EXTRA_OUTPUT_FILE_PATH,
                    photoFile.getAbsolutePath());
            photoCapture.launch(captureIntent);
        } catch (ActivityNotFoundException | IOException | SecurityException
                 | IllegalArgumentException exception) {
            pendingCameraPhoto = null;
            if (photoFile != null) {
                photoFile.delete();
            }
            cameraButton.setEnabled(true);
            Snackbar.make(findViewById(R.id.main), R.string.camera_error,
                    Snackbar.LENGTH_LONG).show();
        }
    }

    private void loadPhoto(Uri uri) {
        aiRunner.cancel();
        foodCrossCheck.onLabelAnalysisStarted();
        final int request = ++imageRequest;
        pendingPhoto = uri;
        setLoading(true);
        if (analysisMode) {
            // Replacing the photo during a check: show that the new one is being read.
            showAnalysisLoading();
            restartScanProgress();
        }
        // A camera image can be very large. Decode a bounded preview off the UI thread.
        imageExecutor.execute(() -> {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, imageSource) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    int longestEdge = Math.max(width, height);
                    if (longestEdge > MAX_PREVIEW_SIZE) {
                        float scale = (float) MAX_PREVIEW_SIZE / longestEdge;
                        decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                Math.max(1, Math.round(height * scale)));
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                runOnUiThread(() -> {
                    if (isDestroyed() || request != imageRequest) {
                        bitmap.recycle();
                        discardSupersededPhoto(uri);
                        return;
                    }
                    String previousPhoto = preferences().getString(PHOTO_STATE, null);
                    selectedPhoto = uri;
                    foodCrossCheck.onLabelPhoto(uri);
                    pendingPhoto = null;
                    persistPhotoAccess(uri);
                    preferences().edit().putString(PHOTO_STATE, uri.toString()).apply();
                    if (previousPhoto != null && !previousPhoto.equals(uri.toString())) {
                        releasePhotoAccess(Uri.parse(previousPhoto));
                    }
                    photoPreview.setImageBitmap(bitmap);
                    showPhotoState(true);
                    setLoading(false);
                    if (analysisMode) {
                        bindThumbnails(Collections.singletonList(bitmap), true);
                        recognizeIngredients(uri, request);
                    } else {
                        // Nothing is read until the user asks for it.
                        photoPreview.announceForAccessibility(getString(R.string.photo_added));
                    }
                });
            } catch (IOException | RuntimeException exception) {
                Log.w("MainActivity", "Unable to decode selected photo", exception);
                runOnUiThread(() -> {
                    if (isDestroyed() || request != imageRequest) {
                        discardSupersededPhoto(uri);
                        return;
                    }
                    pendingPhoto = null;
                    if (photoPreview.getDrawable() == null && uri.equals(selectedPhoto)) {
                        selectedPhoto = null;
                    }
                    if (selectedPhoto == null
                            && uri.toString().equals(preferences().getString(PHOTO_STATE, null))) {
                        preferences().edit().remove(PHOTO_STATE).apply();
                        releasePhotoAccess(uri);
                    } else if (!uri.equals(selectedPhoto)) {
                        deleteCameraPhoto(uri);
                    }
                    if (selectedPhoto != null && photoPreview.getDrawable() == null) {
                        // Recreation kept the committed URI, but its preview still needs loading.
                        loadPhoto(selectedPhoto);
                    } else {
                        setLoading(false);
                        if (analysisMode && selectedPhoto != null) {
                            // The replacement failed; check the photo that is still on screen.
                            rescanCurrentPhoto();
                        } else if (analysisMode) {
                            leaveAnalysisMode(false);
                        }
                    }
                    Snackbar.make(findViewById(R.id.main), R.string.photo_error,
                                    Snackbar.LENGTH_LONG)
                            .setAction(R.string.try_again, view -> openPhotoPicker()).show();
                });
            }
        });
    }

    /**
     * Reads {@code photo} and analyses it. Deliberately takes the <em>URI</em> rather than the
     * preview bitmap: the preview is capped at {@link #MAX_PREVIEW_SIZE} for display, which is the
     * bottom of the band {@code LabelImageDecoder} normalises to, and small print on a tightly
     * cropped panel is lost at that size. Recognising from the URI lets the decoder pick the
     * resolution — including upscaling a small crop — so the recogniser sees the best image we can
     * give it instead of whatever happened to be big enough to draw.
     */
    private void recognizeIngredients(Uri photo, int request) {
        if (settingsOpen) {
            settingsInterruptedScan = true;
            return;
        }
        aiRunner.cancel();
        foodCrossCheck.onLabelAnalysisStarted();
        showAnalysisLoading();
        // Continues from the decode the ring may already be showing; never moves it backwards.
        // A mode switched in settings since the ring started shares it out differently, so restart.
        if (scanProgress.isAi() != usesAi()) {
            restartScanProgress();
        }
        scanProgress.step(ScanProgress.Step.DECODING);
        ocrPipeline.recognize(this, photo, new OcrPipeline.Callback() {
            @Override
            public void onStage(@NonNull OcrPipeline.Stage stage) {
                if (isCurrentImageRequest(request)) {
                    scanProgress.step(stage == OcrPipeline.Stage.PREPARING
                            ? ScanProgress.Step.PREPARING : ScanProgress.Step.RECOGNIZING);
                }
            }

            @Override
            public void onSuccess(@NonNull OcrResult result) {
                if (!isCurrentImageRequest(request)) {
                    return;
                }
                scanProgress.step(ScanProgress.Step.MATCHING);
                String text = result.fullText();
                if (text.trim().isEmpty()) {
                    showAndSaveMessage(photo, text, getString(R.string.ocr_empty_result));
                    return;
                }
                List<String> ingredients = IngredientExtractor.extract(text);
                // The extracted list is what gets analysed; statements such as "may contain nuts"
                // are printed outside the list, so the analyser also sees the full text.
                AllergenDictionary dictionary = AllergenDictionaryProvider.get(MainActivity.this);
                AnalysisReport report = Analyzer.analyse(ingredients, text, dictionary,
                        AllergenPreferences.selectedAllergenSet(MainActivity.this, dictionary));
                if (report.label().isEmpty()) {
                    // Nothing to analyse at all: show the raw text so the user can see what was read.
                    showAndSaveMessage(photo, text,
                            getString(R.string.no_ingredients_found, text));
                    return;
                }
                AnalysisPreferences.Mode mode = AnalysisPreferences.mode(MainActivity.this);
                if (mode == AnalysisPreferences.Mode.MATCHING || report.results().isEmpty()) {
                    showAnalysisReport(photo, report, null);
                } else if (report.label().ingredients().isEmpty()) {
                    showAnalysisReport(photo, AiAnalyzer.incompleteReport(report), null);
                } else if (!hasAiConfiguration(mode)) {
                    showAnalysisReport(photo, AiAnalyzer.incompleteReport(report),
                            ResultAction.CONFIGURE_AI);
                } else {
                    scanProgress.step(ScanProgress.Step.AI_CHECKS);
                    aiRunner.check(report, new AiAnalysisRunner.Callback() {
                        @Override
                        public void onProgress(int completed, int total) {
                            if (isCurrentImageRequest(request)) {
                                scanProgress.aiChecks(completed, total);
                                ((TextView) findViewById(R.id.analysis_loading_title)).setText(
                                        R.string.ai_check_progress);
                            }
                        }

                        @Override
                        public void onResult(AnalysisReport checked) {
                            if (isCurrentImageRequest(request)) {
                                showAnalysisReport(photo, checked, null);
                            }
                        }
                    });
                }
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (isCurrentImageRequest(request)) {
                    showAndSaveMessage(photo, "", getString(R.string.ingredient_read_error));
                }
            }
        });
    }

    private boolean hasAiConfiguration(AnalysisPreferences.Mode mode) {
        if (mode == AnalysisPreferences.Mode.LOCAL_AI) {
            return LocalModelManager.installedModel(this) != null;
        }
        String model = mode == AnalysisPreferences.Mode.DECISIONS
                ? AnalysisPreferences.decisionsModel(this) : AnalysisPreferences.openRouterModel(this);
        return !AnalysisPreferences.openRouterApiKey(this).trim().isEmpty() && !model.trim().isEmpty();
    }

    private void showAnalysisReport(Uri photo, AnalysisReport report, ResultAction action) {
        resultView.showReport(report, AllergenPreferences.showNotDetected(this));
        showResult(action != null ? action
                : report.overall() == AnalysisReport.Status.NO_ALLERGENS_SELECTED
                ? ResultAction.CHOOSE_ALLERGENS
                : report.aiIncomplete() || report.overall() == AnalysisReport.Status.UNREADABLE
                ? ResultAction.RETRY : ResultAction.NONE,
                () -> {
                    ScanHistory.get(this).save(report, photo);
                    foodCrossCheck.onReport(report);
                });
    }

    /**
     * Shows a scan that produced no report, and records it anyway. A failed read is still a scan
     * the user took, and storing the title and body lets the history page reproduce this very
     * screen through the same {@link AnalysisResultView#showMessage} call.
     */
    private void showAndSaveMessage(Uri photo, String rawText, String message) {
        String title = getString(R.string.ingredient_result_title);
        resultView.showMessage(title, message);
        showResult(ResultAction.RETRY,
                () -> ScanHistory.get(this).saveFailure(rawText, title, message, photo));
    }

    /** Re-reads the photo already on screen, if any; a no-op when nothing is loaded. */
    private void rescanCurrentPhoto() {
        Bitmap bitmap = currentBitmap();
        // A photo still decoding will be checked (with current preferences) once it lands.
        if (selectedPhoto == null || bitmap == null || pendingPhoto != null) {
            return;
        }
        restartScanProgress();
        recognizeIngredients(selectedPhoto, ++imageRequest);
    }

    private boolean isCurrentImageRequest(int request) {
        return request == imageRequest && selectedPhoto != null && analysisMode
                && !settingsOpen && !isFinishing() && !isDestroyed();
    }

    private Bitmap currentBitmap() {
        return photoPreview.getDrawable() instanceof BitmapDrawable
                ? ((BitmapDrawable) photoPreview.getDrawable()).getBitmap() : null;
    }

    // ----- Tabs -----

    /**
     * Shows one page or the other. The scan page keeps its own state and its own mode machine
     * untouched while it is hidden, so coming back to it resumes exactly where it was.
     */
    private void showHistory(boolean show) {
        if (historyVisible == show) {
            return;
        }
        historyVisible = show;
        if (show) {
            ensureHistoryFragment();
            HistoryFragment fragment = (HistoryFragment) getSupportFragmentManager()
                    .findFragmentByTag(HISTORY_FRAGMENT);
            if (fragment != null) {
                fragment.refresh();
            }
        } else {
            // Drop any open detail, so returning to history lands on the list.
            getSupportFragmentManager().popBackStackImmediate(
                    null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        contentScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        historyContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        // Pinned outside content_scroll now, so it no longer leaves with it on its own.
        restartButton.setVisibility(
                show || analysisResult.getVisibility() != View.VISIBLE ? View.GONE : View.VISIBLE);
        historyBackCallback.setEnabled(show);
        if (show) {
            HomeMotion.riseIn(historyContainer, dp(24), 200);
        }
    }

    /**
     * Adds the history fragment the first time it is needed. Doing this lazily is not only about
     * startup cost: the history pages include the same result layout as the scan page, so its ids
     * exist twice once they are attached, and keeping them out of the hierarchy until the user
     * opens history keeps {@code Activity#findViewById} unambiguous before then.
     */
    private void ensureHistoryFragment() {
        FragmentManager manager = getSupportFragmentManager();
        if (manager.findFragmentByTag(HISTORY_FRAGMENT) != null) {
            return;
        }
        if (manager.isStateSaved()) {
            // The tab was switched after state was saved, so a committed transaction would throw.
            // Which tab is showing is already in HISTORY_STATE, so onCreate puts this back.
            return;
        }
        manager.beginTransaction()
                .replace(R.id.history_container, new HistoryFragment(), HISTORY_FRAGMENT)
                .commitNow();
    }

    @Override
    public void showScanTab() {
        bottomNav.setSelectedItemId(R.id.tab_scan);
    }

    // ----- The check: start, progress, result, leave -----

    /** Shrinks the photo to the top of the page, hides the home content and reads the label. */
    private void startAnalysis() {
        Bitmap bitmap = currentBitmap();
        if (selectedPhoto == null || bitmap == null || analysisMode
                || loadingOverlay.getVisibility() == View.VISIBLE) {
            return;
        }
        RectF from = HomeMotion.fitCenterBoundsIn(root, photoPreview, bitmap);
        bindThumbnails(Collections.singletonList(bitmap), false);
        TransitionManager.beginDelayedTransition(contentColumn,
                HomeMotion.pageTransition(photoCard, photoStripScroll));
        setAnalysisMode(true);
        contentScroll.scrollTo(0, 0);
        View firstItem = photoStrip.getChildAt(0);
        View firstThumbnail = firstItem.findViewById(R.id.thumbnail_image);
        endPhotoFlight();
        // The whole item (with its edit badge) stays hidden until the photo lands on its card.
        photoFlight = HomeMotion.fly(root, bitmap, from, HomeMotion.boundsIn(root, contentScroll),
                0f, dp(16), firstThumbnail, firstItem,
                () -> HomeMotion.fitCenterBoundsIn(root, firstThumbnail, bitmap));
        HomeMotion.riseIn(analysisGroup, dp(24), 200);
        showAnalysisLoading();
        // Start reading once the photo has landed so the flight gets every frame.
        int request = ++imageRequest;
        root.postDelayed(() -> {
            if (isCurrentImageRequest(request)) {
                recognizeIngredients(selectedPhoto, request);
            }
        }, photoFlight == null ? 0 : HomeMotion.FLIGHT_MS);
    }

    /** Stops a check and puts the photo back where it was, ready to check again. */
    private void cancelAnalysis() {
        if (!analysisMode) {
            return;
        }
        Bitmap bitmap = currentBitmap();
        View firstThumbnail = photoStrip.getChildCount() == 0 ? null
                : photoStrip.getChildAt(0).findViewById(R.id.thumbnail_image);
        RectF from = firstThumbnail == null || bitmap == null ? null
                : HomeMotion.fitCenterBoundsIn(root, firstThumbnail, bitmap);
        leaveAnalysisMode(true);
        if (bitmap != null && from != null && photoPreview.getDrawable() != null) {
            photoFlight = HomeMotion.fly(root, bitmap, from,
                    HomeMotion.boundsIn(root, contentScroll), dp(16), 0f, photoPreview,
                    photoPreview, () -> HomeMotion.fitCenterBoundsIn(root, photoPreview, bitmap));
        }
    }

    /** Clears the photo and the result and returns to an empty home. */
    private void restart() {
        removePhoto();
        leaveAnalysisMode(true);
    }

    private void leaveAnalysisMode(boolean animate) {
        ++imageRequest;
        aiRunner.cancel();
        foodCrossCheck.onLabelAnalysisStarted();
        endPhotoFlight();
        stopScanning();
        // Hidden so the next check comes into view, and starts its ring, from empty.
        scanProgress.cancel();
        analysisLoading.setVisibility(View.GONE);
        // A replacement photo may still be decoding; its result is now dropped, so undo its
        // loading state and remove its file if it was a fresh camera capture.
        if (pendingPhoto != null) {
            if (!pendingPhoto.equals(selectedPhoto)) {
                deleteCameraPhoto(pendingPhoto);
            }
            pendingPhoto = null;
        }
        setLoading(false);
        if (animate) {
            // The photo card fades in with the rest of the home, under the returning photo.
            TransitionManager.beginDelayedTransition(contentColumn,
                    HomeMotion.pageTransition(photoStripScroll));
        }
        setAnalysisMode(false);
        dismissChangePhotoMenu();
        photoStrip.removeAllViews();
        if (selectedPhoto != null && photoPreview.getDrawable() == null) {
            // Left before the restored photo was decoded: finish loading it into the preview.
            loadPhoto(selectedPhoto);
        }
    }

    private void endPhotoFlight() {
        if (photoFlight != null) {
            HomeMotion.Flight flight = photoFlight;
            photoFlight = null;
            flight.end();
        }
    }

    /** Swaps the page between the home layout and the check layout, without animating. */
    private void setAnalysisMode(boolean enabled) {
        analysisMode = enabled;
        analysisBackCallback.setEnabled(enabled);
        boolean hasPhoto = selectedPhoto != null || photoPreview.getDrawable() != null;
        introGroup.setVisibility(enabled ? View.GONE : View.VISIBLE);
        photoCard.setVisibility(enabled ? View.GONE : View.VISIBLE);
        previewActions.setVisibility(!enabled && hasPhoto ? View.VISIBLE : View.GONE);
        tipsGroup.setVisibility(enabled ? View.GONE : View.VISIBLE);
        photoStripScroll.setVisibility(enabled ? View.VISIBLE : View.GONE);
        analysisGroup.setVisibility(enabled ? View.VISIBLE : View.GONE);
        updatePhotoLayout();
        if (!enabled) {
            // analysisResult's own flag would otherwise stay stale at VISIBLE even though its
            // ancestor analysisGroup is now gone, which fools the history-tab restore in
            // showHistory() into bringing restartButton back on the home screen.
            analysisResult.setVisibility(View.GONE);
            // Pinned outside the analysis group now, so it needs its own visibility toggle too.
            restartButton.setVisibility(View.GONE);
        }
        updateCrossCheckVisibility();
    }

    /**
     * Keeps the label and optional front photo together above the result. Both use the same
     * thumbnail card; an empty second slot offers to add the optional photo.
     */
    private void bindThumbnails(List<Bitmap> bitmaps, boolean animate) {
        dismissChangePhotoMenu();
        foodCrossCheck.dismissPhotoMenu();
        stopScanning();
        photoStrip.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        int height = getResources().getDimensionPixelSize(R.dimen.photo_thumbnail_height);
        int maxWidth = getResources().getDimensionPixelSize(R.dimen.photo_thumbnail_max_width);
        int availableWidth = contentColumn.getWidth() > 0 ? contentColumn.getWidth()
                : getResources().getDisplayMetrics().widthPixels - Math.round(dp(40));
        maxWidth = Math.min(maxWidth, Math.max(1, availableWidth / 2 - Math.round(dp(12))));
        List<Bitmap> photos = new ArrayList<>(bitmaps);
        Bitmap front = foodCrossCheck.currentBitmap();
        if (!photos.isEmpty() && front != null) photos.add(front);
        for (int i = 0; i < photos.size(); i++) {
            Bitmap bitmap = photos.get(i);
            View item = inflater.inflate(R.layout.item_photo_thumbnail, photoStrip, false);
            ImageView image = item.findViewById(R.id.thumbnail_image);
            image.setImageBitmap(bitmap);
            // Equal cards keep the pair balanced; fitCenter preserves the complete label/photo.
            image.getLayoutParams().width = Math.min(maxWidth, height);
            View card = item.findViewById(R.id.thumbnail_card);
            final int index = i;
            card.setContentDescription(getString(i == 0
                    ? R.string.crosscheck_label_photo_label : R.string.crosscheck_front_photo_label));
            ViewCompat.replaceAccessibilityAction(card,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                    getString(R.string.replace_photo), null);
            card.setOnClickListener(view -> {
                if (index == 0) showChangePhotoMenu(view, 0);
                else foodCrossCheck.showPhotoMenu(view);
            });
            photoStrip.addView(item);
            if (animate) {
                item.setScaleX(0.9f);
                item.setScaleY(0.9f);
                item.setAlpha(0f);
                item.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
                        .setInterpolator(HomeMotion.EMPHASIZED).start();
            }
        }
        if (!photos.isEmpty() && front == null) {
            View item = inflater.inflate(R.layout.item_add_front_photo, photoStrip, false);
            View card = item.findViewById(R.id.front_thumbnail_add_card);
            card.getLayoutParams().width = Math.min(maxWidth, height);
            View button = item.findViewById(R.id.front_thumbnail_add_button);
            card.setContentDescription(getString(R.string.crosscheck_add_photo));
            card.setEnabled(!foodCrossCheck.isPhotoLoading());
            button.setEnabled(!foodCrossCheck.isPhotoLoading());
            card.setOnClickListener(foodCrossCheck::showPhotoMenu);
            button.setOnClickListener(foodCrossCheck::showPhotoMenu);
            photoStrip.addView(item);
        }
    }

    private void onFrontPhotoChanged() {
        if (isFinishing() || isDestroyed()) return;
        updatePhotoLayout();
        Bitmap label = currentBitmap();
        if (analysisMode && pendingPhoto == null && label != null) {
            endPhotoFlight();
            bindThumbnails(Collections.singletonList(label), false);
            if (analysisLoading.getVisibility() == View.VISIBLE) startScanning();
        }
    }

    /** Offers to swap the photo at {@code index} for another one from the library or camera. */
    private void showChangePhotoMenu(View anchor, int index) {
        // Only one photo for now; the index is where a multi-photo check will route the change.
        dismissChangePhotoMenu();
        PopupMenu menu = new PopupMenu(this, anchor);
        changePhotoMenu = menu;
        menu.setOnDismissListener(dismissed -> {
            if (changePhotoMenu == dismissed) {
                changePhotoMenu = null;
            }
        });
        menu.getMenu().add(0, 1, 0, R.string.change_photo_choose);
        menu.getMenu().add(0, 2, 1, R.string.change_photo_camera);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                openPhotoPicker();
            } else {
                takePhoto();
            }
            return true;
        });
        menu.show();
    }

    /** The menu is anchored to a thumbnail, so it must not outlive the thumbnails. */
    private void dismissChangePhotoMenu() {
        if (changePhotoMenu != null) {
            PopupMenu menu = changePhotoMenu;
            changePhotoMenu = null;
            menu.dismiss();
        }
    }

    private void showAnalysisLoading() {
        resultAction = ResultAction.NONE;
        ((TextView) findViewById(R.id.analysis_loading_title)).setText(R.string.reading_ingredients);
        if (analysisLoading.getVisibility() != View.VISIBLE) {
            TransitionManager.beginDelayedTransition(contentColumn,
                    HomeMotion.pageTransition(photoCard, photoStripScroll));
            // A scan coming into view starts from an empty ring; one already showing carries on.
            restartScanProgress();
        }
        analysisResult.setVisibility(View.GONE);
        restartButton.setVisibility(View.GONE);
        analysisLoading.setVisibility(View.VISIBLE);
        updateCrossCheckVisibility();
        startScanning();
    }

    /**
     * Empties the ring for a new scan, shared out for the detection mode it will run in: the AI
     * modes save most of the ring for their checks, matching spends it all on reading the label.
     */
    private void restartScanProgress() {
        scanProgress.reset(usesAi());
        scanProgress.step(ScanProgress.Step.DECODING);
    }

    /** Whether this scan will run AI checks; a mode that is not set up only reads and matches. */
    private boolean usesAi() {
        AnalysisPreferences.Mode mode = AnalysisPreferences.mode(this);
        return mode != AnalysisPreferences.Mode.MATCHING && hasAiConfiguration(mode);
    }

    /** Replaces progress with the final result, then records it if completion was not cancelled. */
    private void showResult(ResultAction action, Runnable onShown) {
        Runnable reveal = () -> {
            revealResult(action);
            onShown.run();
        };
        if (analysisLoading.getVisibility() == View.VISIBLE) {
            // Let the ring close first: a result over a part-filled ring reads as cut short.
            // Cancelling or restarting the scan also drops the pending history save.
            scanProgress.finish(reveal);
        } else {
            reveal.run();
        }
    }

    private void revealResult(ResultAction action) {
        // A fade still running from the last page change would otherwise hide the result again.
        TransitionManager.endTransitions(contentColumn);
        stopScanning();
        resultAction = action;
        if (action == ResultAction.NONE) {
            resultSecondaryButton.setVisibility(View.GONE);
        } else {
            resultSecondaryButton.setVisibility(View.VISIBLE);
            resultSecondaryButton.setText(action == ResultAction.CHOOSE_ALLERGENS
                    ? R.string.choose_allergens_action : action == ResultAction.CONFIGURE_AI
                    ? R.string.configure_ai_action : R.string.try_again);
        }
        analysisLoading.setVisibility(View.GONE);
        analysisResult.setVisibility(View.VISIBLE);
        updateCrossCheckVisibility();
        restartButton.setVisibility(historyVisible ? View.GONE : View.VISIBLE);
        if (historyVisible) {
            return;
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            HomeMotion.revealChildren(analysisResult, 0);
            HomeMotion.popIn(resultView.verdictIcon(), 120);
            HomeMotion.riseIn(restartButton, dp(16), 360);
        }
        analysisResult.announceForAccessibility(
                getString(R.string.result_ready, resultView.title()));
    }

    private void onResultAction() {
        if (resultAction == ResultAction.CHOOSE_ALLERGENS) {
            openAllergenPreferences();
        } else if (resultAction == ResultAction.CONFIGURE_AI) {
            openSettings(true);
        } else if (resultAction == ResultAction.RETRY) {
            rescanCurrentPhoto();
        }
    }

    private void startScanning() {
        stopScanning();
        // Only the ingredient photo is being read by OCR; the front photo has its own check.
        for (int i = 0; i < Math.min(1, photoStrip.getChildCount()); i++) {
            View item = photoStrip.getChildAt(i);
            ValueAnimator animator = HomeMotion.scan(item.findViewById(R.id.thumbnail_scan_line),
                    item.findViewById(R.id.thumbnail_image));
            if (animator != null) {
                scanAnimators.add(animator);
            }
        }
    }

    private void stopScanning() {
        for (ValueAnimator animator : scanAnimators) {
            animator.cancel();
        }
        scanAnimators.clear();
        for (int i = 0; i < photoStrip.getChildCount(); i++) {
            View line = photoStrip.getChildAt(i).findViewById(R.id.thumbnail_scan_line);
            if (line == null) continue;
            line.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> line.setVisibility(View.INVISIBLE)).start();
        }
    }

    private void setLoading(boolean loading) {
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        importButton.setEnabled(!loading);
        updateCameraButton();
        replaceButton.setEnabled(!loading);
        startAnalysisButton.setEnabled(!loading);
        updateCrossCheckVisibility();
    }

    private void updateCrossCheckVisibility() {
        foodCrossCheck.setVisible(selectedPhoto != null && pendingPhoto == null
                && (!analysisMode || analysisResult.getVisibility() == View.VISIBLE));
    }

    private void updatePhotoLayout() {
        boolean hasPhoto = selectedPhoto != null || photoPreview.getDrawable() != null;
        LinearLayout.LayoutParams mainPhoto = (LinearLayout.LayoutParams) photoCard.getLayoutParams();
        mainPhoto.width = hasPhoto ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
        mainPhoto.weight = hasPhoto ? 1f : 0f;
        photoCard.setLayoutParams(mainPhoto);
        photoInputRow.setVisibility(analysisMode ? View.GONE : View.VISIBLE);
        frontPhotoCard.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        frontPhotoHint.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
    }

    /**
     * The camera button is usable unless a capture is already outstanding or the screen is busy.
     *
     * <p>Decided in one place rather than at each call site, so no path can leave the button
     * disabled with nothing left to re-enable it. That is the failure that used to strand the
     * camera for the rest of the session after backing out of the capture screen.
     */
    private void updateCameraButton() {
        cameraButton.setEnabled(loadingOverlay.getVisibility() != View.VISIBLE
                && pendingCameraPhoto == null);
    }

    private void showPhotoState(boolean hasPhoto) {
        findViewById(R.id.empty_state).setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
        findViewById(R.id.preview_state).setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        previewActions.setVisibility(hasPhoto && !analysisMode ? View.VISIBLE : View.GONE);
        updatePhotoLayout();
        ((TextView) findViewById(R.id.eyebrow)).setText(
                hasPhoto ? R.string.preview_eyebrow : R.string.new_scan);
        ((TextView) findViewById(R.id.home_title)).setText(
                hasPhoto ? R.string.preview_title : R.string.home_title);
        ((TextView) findViewById(R.id.home_subtitle)).setText(
                hasPhoto ? R.string.preview_subtitle : R.string.home_subtitle);
    }

    private void removePhoto() {
        ++imageRequest;
        aiRunner.cancel();
        foodCrossCheck.onLabelPhoto(null);
        if (selectedPhoto != null) {
            releasePhotoAccess(selectedPhoto);
        }
        if (pendingPhoto != null && !pendingPhoto.equals(selectedPhoto)) {
            deleteCameraPhoto(pendingPhoto);
        }
        selectedPhoto = null;
        pendingPhoto = null;
        preferences().edit().remove(PHOTO_STATE).apply();
        photoPreview.setImageDrawable(null);
        setLoading(false);
        showPhotoState(false);
    }

    /** A camera capture whose decode was overtaken by another photo is no longer needed. */
    private void discardSupersededPhoto(Uri uri) {
        if (!isDestroyed() && !uri.equals(selectedPhoto) && !uri.equals(pendingPhoto)) {
            deleteCameraPhoto(uri);
        }
    }

    private void persistPhotoAccess(Uri uri) {
        if (isCameraPhoto(uri)) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException exception) {
            // Some providers only grant temporary access. A later read can be retried.
        }
    }

    private void releasePhotoAccess(Uri uri) {
        if (isCameraPhoto(uri)) {
            deleteCameraPhoto(uri);
            return;
        }
        try {
            getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException exception) {
            // A temporary grant does not need to be released explicitly.
        }
    }

    private boolean isCameraPhoto(Uri uri) {
        return "content".equals(uri.getScheme())
                && (getPackageName() + ".fileprovider").equals(uri.getAuthority())
                && uri.getPathSegments().size() == 2
                && "photos".equals(uri.getPathSegments().get(0));
    }

    private void deleteCameraPhoto(Uri uri) {
        if (!isCameraPhoto(uri)) {
            return;
        }
        String filename = uri.getLastPathSegment();
        if (filename == null || !filename.startsWith("capture_") || !filename.endsWith(".jpg")) {
            return;
        }
        try {
            File directory = new File(getFilesDir(), "photos").getCanonicalFile();
            File photo = new File(directory, filename).getCanonicalFile();
            // Only remove files created by this app, never a selected gallery image.
            if (directory.equals(photo.getParentFile())) {
                photo.delete();
            }
        } catch (IOException exception) {
            // A file that cannot be resolved safely is left untouched.
        }
    }

    private SharedPreferences preferences() {
        return AllergenPreferences.preferences(this);
    }

    private void openSettings() {
        openSettings(false);
    }

    /** Always through the launcher, so a save from either section refreshes this screen. */
    private void openSettings(boolean showModel) {
        settingsOpen = true;
        foodCrossCheck.suspend();
        if (analysisMode && analysisLoading.getVisibility() == View.VISIBLE) {
            settingsInterruptedScan = true;
            aiRunner.cancel();
            // A ring closing on a finished scan must not reveal its result behind settings.
            scanProgress.cancel();
        }
        settingsLauncher.launch(new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, showModel));
    }

    private void openAllergenPreferences() {
        if (!getSupportFragmentManager().isStateSaved() && !isAllergenDialogOpen()) {
            new AllergenPreferencesDialogFragment().showNow(getSupportFragmentManager(),
                    AllergenPreferencesDialogFragment.TAG);
        }
    }

    private boolean isAllergenDialogOpen() {
        return getSupportFragmentManager().findFragmentByTag(
                AllergenPreferencesDialogFragment.TAG) != null;
    }

    private void onAllergenPreferencesSaved() {
        updateAllergenSummary();
        Snackbar.make(findViewById(R.id.main), R.string.preferences_saved,
                Snackbar.LENGTH_SHORT).show();
        // The verdict depends on the chosen allergens, so re-check a label that is on screen.
        if (analysisMode) {
            rescanCurrentPhoto();
        }
    }

    /**
     * Refreshes the count and the chips from the same dictionary-resolved selection the analyser
     * is given, so the number on the button can never claim more allergens than are being checked.
     */
    private void updateAllergenSummary() {
        Set<Allergen> saved = AllergenPreferences.selectedAllergenSet(
                this, AllergenDictionaryProvider.get(this));
        int count = saved.size();
        allergensButton.setText(count == 0 ? getString(R.string.set_allergens)
                : getResources().getQuantityString(R.plurals.allergens_selected, count, count));

        allergenChips.removeAllViews();
        // Already in dictionary order, which is the order the pickers list them in.
        for (Allergen allergen : saved) {
            Chip chip = new Chip(this);
            chip.setText(allergen.label());
            chip.setClickable(false);
            chip.setFocusable(false);
            chip.setEnsureMinTouchTargetSize(false);
            chip.setChipStrokeWidth(0f);
            // Small, read-only tags; the button above opens the editor.
            chip.setChipMinHeight(dp(24));
            chip.setChipStartPadding(0f);
            chip.setChipEndPadding(0f);
            chip.setTextStartPadding(dp(8));
            chip.setTextEndPadding(dp(8));
            chip.setChipBackgroundColor(ColorStateList.valueOf(MaterialColors.getColor(
                    allergenChips, com.google.android.material.R.attr.colorSecondaryContainer)));
            chip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            chip.setTextColor(MaterialColors.getColor(allergenChips,
                    com.google.android.material.R.attr.colorOnSecondaryContainer));
            allergenChips.addView(chip);
        }
        allergenChips.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onStart() {
        super.onStart();
        modelDownloadController.start();
    }

    @Override
    protected void onStop() {
        modelDownloadController.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Safety net: returning to this screen always leaves a usable camera button, whatever route
        // was taken back. Cheap, and it means even a dropped activity result cannot strand it.
        updateCameraButton();
        if (!analysisMode && !historyVisible && !isAllergenDialogOpen()) {
            modelDownloadController.offerIntroduction();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        foodCrossCheck.saveState(outState);
        if (selectedPhoto != null) {
            outState.putString(PHOTO_STATE, selectedPhoto.toString());
        }
        if (pendingPhoto != null) {
            outState.putString(PENDING_PHOTO_STATE, pendingPhoto.toString());
        }
        if (pendingCameraPhoto != null) {
            outState.putString(CAMERA_STATE, pendingCameraPhoto.toString());
        }
        outState.putBoolean(ANALYSIS_STATE, analysisMode);
        outState.putBoolean(SETTINGS_OPEN_STATE, settingsOpen);
        outState.putBoolean(SETTINGS_INTERRUPTED_STATE, settingsInterruptedScan);
        outState.putBoolean(HISTORY_STATE, historyVisible);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        ++imageRequest;
        dismissChangePhotoMenu();
        endPhotoFlight();
        for (ValueAnimator animator : scanAnimators) {
            animator.cancel();
        }
        scanAnimators.clear();
        scanProgress.cancel();
        ocrPipeline.close();
        aiRunner.close();
        foodCrossCheck.close();
        imageExecutor.shutdownNow();
        super.onDestroy();
    }
}
