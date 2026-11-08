package com.example.allergycheck;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.AllergenDictionary;
import com.example.allergycheck.analysis.AnalysisReport;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.ingredients.IngredientExtractor;
import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PHOTO_STATE = "selected_photo";
    private static final String PENDING_PHOTO_STATE = "pending_photo";
    private static final String CAMERA_STATE = "pending_camera_photo";
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
    private MaterialButton allergensButton;
    private ChipGroup allergenChips;
    private OcrPipeline ocrPipeline;
    private AlertDialog ingredientDialog;
    private String ingredientDialogTitle;
    private String ingredientDialogMessage;
    private boolean ingredientDialogScanning;
    private boolean ingredientDialogRetry;
    private boolean ingredientDialogChooseAllergens;
    private boolean activityResumed;
    private ModelDownloadController modelDownloadController;

    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    onAllergenPreferencesSaved();
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    loadPhoto(uri);
                }
            });

    private final ActivityResultLauncher<Uri> photoCapture =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                Uri capturedPhoto = pendingCameraPhoto;
                pendingCameraPhoto = null;
                if (capturedPhoto == null) {
                    return;
                }
                if (success) {
                    loadPhoto(capturedPhoto);
                } else {
                    deleteCameraPhoto(capturedPhoto);
                    cameraButton.setEnabled(loadingOverlay.getVisibility() != View.VISIBLE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        allergensButton = findViewById(R.id.allergens_button);
        allergenChips = findViewById(R.id.allergen_chips);
        ocrPipeline = new OcrPipeline();
        modelDownloadController = new ModelDownloadController(this);

        getSupportFragmentManager().setFragmentResultListener(
                AllergenPreferencesDialogFragment.RESULT_KEY, this, (key, result) ->
                        findViewById(R.id.main).post(() -> {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (result.getBoolean(AllergenPreferencesDialogFragment.SAVED)) {
                                onAllergenPreferencesSaved();
                            } else {
                                showIngredientDialog();
                            }
                        }));

        importButton.setOnClickListener(view -> openPhotoPicker());
        cameraButton.setOnClickListener(view -> takePhoto());
        replaceButton.setOnClickListener(view -> openPhotoPicker());
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
            photoCapture.launch(pendingCameraPhoto);
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
        final int request = ++imageRequest;
        clearIngredientDialog();
        pendingPhoto = uri;
        setLoading(true);
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
                        return;
                    }
                    String previousPhoto = preferences().getString(PHOTO_STATE, null);
                    selectedPhoto = uri;
                    pendingPhoto = null;
                    persistPhotoAccess(uri);
                    preferences().edit().putString(PHOTO_STATE, uri.toString()).apply();
                    if (previousPhoto != null && !previousPhoto.equals(uri.toString())) {
                        releasePhotoAccess(Uri.parse(previousPhoto));
                    }
                    photoPreview.setImageBitmap(bitmap);
                    showPhotoState(true);
                    setLoading(false);
                    photoPreview.announceForAccessibility(getString(R.string.photo_added));
                    recognizeIngredients(bitmap, request);
                });
            } catch (IOException | RuntimeException exception) {
                Log.w("MainActivity", "Unable to decode selected photo", exception);
                runOnUiThread(() -> {
                    if (isDestroyed() || request != imageRequest) {
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
                    }
                    Snackbar.make(findViewById(R.id.main), R.string.photo_error,
                                    Snackbar.LENGTH_LONG)
                            .setAction(R.string.try_again, view -> openPhotoPicker()).show();
                });
            }
        });
    }

    private void recognizeIngredients(Bitmap bitmap, int request) {
        updateIngredientDialog(getString(R.string.reading_ingredients),
                getString(R.string.reading_ingredients_message), true, false, false);
        ocrPipeline.recognize(bitmap, new OcrPipeline.Callback() {
            @Override
            public void onSuccess(@NonNull OcrResult result) {
                if (!isCurrentImageRequest(request)) {
                    return;
                }
                String text = result.fullText();
                if (text.trim().isEmpty()) {
                    updateIngredientDialog(getString(R.string.ingredient_result_title),
                            getString(R.string.ocr_empty_result), false, true, false);
                    return;
                }
                List<String> ingredients = IngredientExtractor.extract(text);
                // The extracted list is what gets analysed; statements such as "may contain nuts"
                // are printed outside the list, so the analyser also sees the full text.
                AllergenDictionary dictionary = AllergenDictionaryProvider.get(MainActivity.this);
                AnalysisReport report = Analyzer.analyse(ingredients, text, dictionary,
                        AllergenPreferences.selectedAllergenSet(MainActivity.this, dictionary));
                if (ingredients.isEmpty() && report.label().statements().isEmpty()) {
                    // Nothing to analyse at all: show the raw text so the user can see what was read.
                    updateIngredientDialog(getString(R.string.ingredient_result_title),
                            getString(R.string.no_ingredients_found, text), false, true, false);
                    return;
                }
                AnalysisMessage message = new AnalysisMessage(getResources());
                boolean needsAllergens =
                        report.overall() == AnalysisReport.Status.NO_ALLERGENS_SELECTED;
                updateIngredientDialog(message.title(report), message.body(report), false,
                        report.overall() == AnalysisReport.Status.UNREADABLE, needsAllergens);
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (isCurrentImageRequest(request)) {
                    updateIngredientDialog(getString(R.string.ingredient_result_title),
                            getString(R.string.ingredient_read_error), false, true, false);
                }
            }
        });
    }

    /** Re-reads the photo already on screen, if any; a no-op when nothing is loaded. */
    private void rescanCurrentPhoto() {
        if (selectedPhoto == null || !(photoPreview.getDrawable() instanceof BitmapDrawable)) {
            return;
        }
        recognizeIngredients(((BitmapDrawable) photoPreview.getDrawable()).getBitmap(),
                ++imageRequest);
    }

    private boolean isCurrentImageRequest(int request) {
        return request == imageRequest && selectedPhoto != null && !isFinishing() && !isDestroyed();
    }

    private void updateIngredientDialog(String title, String message, boolean scanning,
                                        boolean retry, boolean chooseAllergens) {
        ingredientDialogTitle = title;
        ingredientDialogMessage = message;
        ingredientDialogScanning = scanning;
        ingredientDialogRetry = retry;
        ingredientDialogChooseAllergens = chooseAllergens;
        showIngredientDialog();
    }

    private void showIngredientDialog() {
        if (!activityResumed || isFinishing() || isDestroyed() || ingredientDialogTitle == null
                || isAllergenDialogOpen()) {
            return;
        }
        dismissIngredientDialog();
        boolean scanning = ingredientDialogScanning;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(ingredientDialogTitle)
                .setMessage(ingredientDialogMessage)
                .setPositiveButton(scanning ? android.R.string.cancel : android.R.string.ok,
                        (dialog, which) -> {
                            if (scanning) {
                                ++imageRequest;
                            }
                        })
                .setOnCancelListener(dialog -> {
                    if (scanning) {
                        ++imageRequest;
                    }
                });
        if (ingredientDialogChooseAllergens) {
            // Nothing could be checked yet; send the user straight to the allergen picker.
            builder.setNeutralButton(R.string.choose_allergens_action,
                    (dialog, which) -> openAllergenPreferences());
        } else if (ingredientDialogRetry) {
            builder.setNeutralButton(R.string.try_again, (dialog, which) -> rescanCurrentPhoto());
        }
        ingredientDialog = builder.create();
        ingredientDialog.setOnDismissListener(dialog -> {
            if (ingredientDialog == dialog) {
                ingredientDialog = null;
                ingredientDialogTitle = null;
                ingredientDialogMessage = null;
            }
        });
        ingredientDialog.show();
    }

    private void dismissIngredientDialog() {
        if (ingredientDialog != null) {
            ingredientDialog.setOnDismissListener(null);
            ingredientDialog.dismiss();
            ingredientDialog = null;
        }
    }

    private void clearIngredientDialog() {
        dismissIngredientDialog();
        ingredientDialogTitle = null;
        ingredientDialogMessage = null;
    }

    private void setLoading(boolean loading) {
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        importButton.setEnabled(!loading);
        cameraButton.setEnabled(!loading && pendingCameraPhoto == null);
        replaceButton.setEnabled(!loading);
    }

    private void showPhotoState(boolean hasPhoto) {
        findViewById(R.id.empty_state).setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
        findViewById(R.id.preview_state).setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        replaceButton.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.eyebrow)).setText(
                hasPhoto ? R.string.preview_eyebrow : R.string.new_scan);
        ((TextView) findViewById(R.id.home_title)).setText(
                hasPhoto ? R.string.preview_title : R.string.home_title);
        ((TextView) findViewById(R.id.home_subtitle)).setText(
                hasPhoto ? R.string.preview_subtitle : R.string.home_subtitle);
    }

    private void removePhoto() {
        ++imageRequest;
        clearIngredientDialog();
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
        settingsLauncher.launch(new Intent(this, SettingsActivity.class));
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
        // The verdict depends on the chosen allergens, so re-check the current label.
        rescanCurrentPhoto();
    }

    private void updateAllergenSummary() {
        Set<String> saved = AllergenPreferences.selectedAllergens(this);
        int count = saved.size();
        allergensButton.setText(count == 0 ? getString(R.string.set_allergens)
                : getResources().getQuantityString(R.plurals.allergens_selected, count, count));

        allergenChips.removeAllViews();
        // Keep chips in the same order as the picker rather than HashSet order.
        for (Allergen allergen : AllergenDictionaryProvider.get(this).allergens()) {
            if (!saved.contains(allergen.label())) {
                continue;
            }
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
        activityResumed = true;
        showIngredientDialog();
        if (ingredientDialogTitle == null && !isAllergenDialogOpen()) {
            modelDownloadController.offerIntroduction();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        dismissIngredientDialog();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (selectedPhoto != null) {
            outState.putString(PHOTO_STATE, selectedPhoto.toString());
        }
        if (pendingPhoto != null) {
            outState.putString(PENDING_PHOTO_STATE, pendingPhoto.toString());
        }
        if (pendingCameraPhoto != null) {
            outState.putString(CAMERA_STATE, pendingCameraPhoto.toString());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        ++imageRequest;
        clearIngredientDialog();
        ocrPipeline.close();
        imageExecutor.shutdownNow();
        super.onDestroy();
    }
}
