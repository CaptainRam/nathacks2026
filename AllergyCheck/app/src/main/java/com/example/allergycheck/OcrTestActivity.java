package com.example.allergycheck;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
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

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.Analyzer;
import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.example.allergycheck.ocr.PassThroughFilter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.EnumSet;
import java.util.Set;

/**
 * Throwaway screen for exercising {@link OcrPipeline}: pick an image, see the text that comes back.
 *
 * <p>Deliberately separate from {@link MainActivity} so the real UI can be built independently.
 * It has its own launcher icon ("OCR Test"), or start it directly with:
 * <pre>adb shell am start -n com.example.allergycheck/.OcrTestActivity</pre>
 *
 * <p>Delete this class, {@code activity_ocr_test.xml}, its strings and its manifest entry once the
 * real label-capture screen exists. The {@code ocr} package is the part meant to survive.
 */
public class OcrTestActivity extends AppCompatActivity {

    private OcrPipeline ocrPipeline;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;

    private ImageView imagePreview;
    private ProgressBar progress;
    private TextView ocrOutput;
    private MaterialButton allergensButton;
    private MaterialButton analyseButton;

    /** Stand-in for the home screen's saved allergen preferences; everything on by default. */
    private final Set<Allergen> selectedAllergens = EnumSet.allOf(Allergen.class);

    /** The last successful OCR read, ready to analyse. {@code null} until one succeeds. */
    private OcrResult lastOcrResult;

    /** Incremented per image so a slow, superseded recognition can be recognised and ignored. */
    private int requestId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_ocr_test);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ocr_test_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        imagePreview = findViewById(R.id.image_preview);
        progress = findViewById(R.id.progress);
        ocrOutput = findViewById(R.id.ocr_output);

        // Swap PassThroughFilter for new IngredientListFilter() (same package) to restrict output
        // to the ingredients declaration — nothing else in this class needs to change.
        ocrPipeline = new OcrPipeline(new PassThroughFilter());

        // Must be registered before the activity reaches STARTED, hence here rather than in the
        // click listener. minSdk 34 means the photo picker needs no storage permission.
        pickImage = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        runOcr(uri);
                    }
                    // A null uri means the user backed out of the picker; leave the screen as-is.
                });

        allergensButton = findViewById(R.id.allergens_button);
        analyseButton = findViewById(R.id.analyse_button);
        allergensButton.setOnClickListener(v -> showAllergenPicker());
        analyseButton.setOnClickListener(v -> {
            if (lastOcrResult != null) {
                // Empty OCR results are analysed too: they come back UNREADABLE, never "clear".
                startActivity(ResultsActivity.newIntent(this,
                        Analyzer.analyse(lastOcrResult, selectedAllergens)));
            }
        });
        updateAllergenButton();

        findViewById(R.id.pick_image_button).setOnClickListener(v ->
                pickImage.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
    }

    private void runOcr(@NonNull Uri imageUri) {
        imagePreview.setImageURI(imageUri);
        imagePreview.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        ocrOutput.setText("");
        lastOcrResult = null;
        analyseButton.setEnabled(false);

        // Results for an image the user has since replaced must not overwrite the newer one.
        final int thisRequest = ++requestId;

        ocrPipeline.recognize(this, imageUri, new OcrPipeline.Callback() {
            @Override
            public void onSuccess(@NonNull OcrResult result) {
                if (thisRequest != requestId) {
                    return;
                }
                progress.setVisibility(View.GONE);
                lastOcrResult = result;
                analyseButton.setEnabled(true);
                // "Found no text" and "could not read the image" are shown differently on purpose:
                // neither may be presented as a clean, allergen-free read.
                if (result.isEmpty()) {
                    ocrOutput.setText(R.string.ocr_empty_result);
                } else {
                    ocrOutput.setText(result.fullText());
                }
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (thisRequest != requestId) {
                    return;
                }
                progress.setVisibility(View.GONE);
                String message = error.getMessage() != null
                        ? error.getMessage()
                        : error.getClass().getSimpleName();
                ocrOutput.setText(getString(R.string.ocr_error, message));
            }
        });
    }

    private void updateAllergenButton() {
        allergensButton.setText(getString(R.string.choose_allergens, selectedAllergens.size()));
    }

    private void showAllergenPicker() {
        Allergen[] all = Allergen.values();
        String[] labels = new String[all.length];
        boolean[] checked = new boolean[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i].label();
            checked[i] = selectedAllergens.contains(all[i]);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_allergens_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.dialog_done, (dialog, which) -> {
                    selectedAllergens.clear();
                    for (int i = 0; i < all.length; i++) {
                        if (checked[i]) {
                            selectedAllergens.add(all[i]);
                        }
                    }
                    updateAllergenButton();
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        ocrPipeline.close();
        super.onDestroy();
    }
}
