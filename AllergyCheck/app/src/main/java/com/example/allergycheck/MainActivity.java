package com.example.allergycheck;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PHOTO_STATE = "selected_photo";
    private static final String PREFERENCES = "allergy_preferences";
    private static final String ALLERGENS = "selected_allergens";
    private static final int MAX_PREVIEW_SIZE = 1600;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private Uri selectedPhoto;
    private Uri pendingPhoto;
    private int imageRequest;
    private View loadingOverlay;
    private ImageView photoPreview;
    private MaterialButton importButton;
    private MaterialButton replaceButton;
    private MaterialButton allergensButton;
    private ChipGroup allergenChips;

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    loadPhoto(uri);
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
        replaceButton = findViewById(R.id.replace_button);
        allergensButton = findViewById(R.id.allergens_button);
        allergenChips = findViewById(R.id.allergen_chips);

        importButton.setOnClickListener(view -> openPhotoPicker());
        replaceButton.setOnClickListener(view -> openPhotoPicker());
        findViewById(R.id.empty_state).setOnClickListener(view -> openPhotoPicker());
        findViewById(R.id.remove_button).setOnClickListener(view -> removePhoto());
        findViewById(R.id.profile_button).setOnClickListener(view -> showAllergenPreferences());
        allergensButton.setOnClickListener(view -> showAllergenPreferences());
        updateAllergenSummary();

        String savedPhoto = savedInstanceState != null
                ? savedInstanceState.getString(PHOTO_STATE)
                : preferences().getString(PHOTO_STATE, null);
        if (savedPhoto != null) {
            loadPhoto(Uri.parse(savedPhoto));
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

    private void loadPhoto(Uri uri) {
        final int request = ++imageRequest;
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
                });
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> {
                    if (isDestroyed() || request != imageRequest) {
                        return;
                    }
                    pendingPhoto = null;
                    setLoading(false);
                    if (selectedPhoto == null
                            && uri.toString().equals(preferences().getString(PHOTO_STATE, null))) {
                        preferences().edit().remove(PHOTO_STATE).apply();
                        releasePhotoAccess(uri);
                    }
                    Snackbar.make(findViewById(R.id.main), R.string.photo_error,
                                    Snackbar.LENGTH_LONG)
                            .setAction(R.string.try_again, view -> openPhotoPicker()).show();
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        loadingOverlay.setVisibility(loading ? View.VISIBLE : View.GONE);
        importButton.setEnabled(!loading);
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
        if (selectedPhoto != null) {
            releasePhotoAccess(selectedPhoto);
        }
        selectedPhoto = null;
        pendingPhoto = null;
        preferences().edit().remove(PHOTO_STATE).apply();
        photoPreview.setImageDrawable(null);
        setLoading(false);
        showPhotoState(false);
    }

    private void persistPhotoAccess(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException exception) {
            // Some providers only grant temporary access. A later read can be retried.
        }
    }

    private void releasePhotoAccess(Uri uri) {
        try {
            getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException exception) {
            // A temporary grant does not need to be released explicitly.
        }
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFERENCES, MODE_PRIVATE);
    }

    private void showAllergenPreferences() {
        String[] options = getResources().getStringArray(R.array.allergen_options);
        Set<String> saved = preferences().getStringSet(ALLERGENS, new HashSet<>());
        boolean[] checked = new boolean[options.length];
        for (int index = 0; index < options.length; index++) {
            checked[index] = saved.contains(options[index]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.allergens_title)
                .setMultiChoiceItems(options, checked, (dialog, index, isChecked) ->
                        checked[index] = isChecked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save_preferences, (dialog, which) -> {
                    Set<String> selected = new HashSet<>();
                    for (int index = 0; index < options.length; index++) {
                        if (checked[index]) {
                            selected.add(options[index]);
                        }
                    }
                    preferences().edit().putStringSet(ALLERGENS, selected).apply();
                    updateAllergenSummary();
                    Snackbar.make(findViewById(R.id.main), R.string.preferences_saved,
                            Snackbar.LENGTH_SHORT).show();
                })
                .show();
    }

    private void updateAllergenSummary() {
        Set<String> saved = preferences().getStringSet(ALLERGENS, new HashSet<>());
        int count = saved.size();
        allergensButton.setText(count == 0 ? getString(R.string.set_allergens)
                : getResources().getQuantityString(R.plurals.allergens_selected, count, count));

        allergenChips.removeAllViews();
        // Keep chips in the same order as the picker rather than HashSet order.
        for (String option : getResources().getStringArray(R.array.allergen_options)) {
            if (!saved.contains(option)) {
                continue;
            }
            Chip chip = new Chip(this);
            chip.setText(option);
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        Uri photoToRestore = pendingPhoto != null ? pendingPhoto : selectedPhoto;
        if (photoToRestore != null) {
            outState.putString(PHOTO_STATE, photoToRestore.toString());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        ++imageRequest;
        imageExecutor.shutdownNow();
        super.onDestroy();
    }
}
