package com.example.allergycheck;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
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

import com.example.allergycheck.ingredients.LlmIngredientReader;
import com.example.allergycheck.ocr.ImagePreparer;
import com.example.allergycheck.ocr.LabelImageDecoder;
import com.example.allergycheck.ocr.LabelImagePreparer;
import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.example.allergycheck.ocr.PassThroughFilter;
import com.example.allergycheck.ocr.PassThroughPreparer;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.List;

/**
 * Throwaway screen for exercising {@link OcrPipeline}: pick an image, see the text that comes back.
 *
 * <p>Deliberately separate from {@link MainActivity} so the real UI can be built independently.
 * It has its own launcher icon ("OCR Test"), or start it directly with:
 * <pre>adb shell am start -n com.example.allergycheck/.OcrTestActivity</pre>
 *
 * <p>The "Enhance image" switch picks between {@link LabelImagePreparer} and
 * {@link PassThroughPreparer}, so the same photo can be run both ways; the line, character and
 * timing counts under the preview are what you compare. Preparation reads better on average but not
 * on every label — it helps most when contrast is the problem and barely at all otherwise — so this
 * switch is how you check a real photograph against the synthetic fixtures in
 * {@code OcrPipelineTest}.
 *
 * <p>Delete this class, {@code activity_ocr_test.xml}, its strings and its manifest entry once the
 * real label-capture screen exists. The {@code ocr} package is the part meant to survive.
 */
public class OcrTestActivity extends AppCompatActivity {

    private OcrPipeline ocrPipeline;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;

    /** Renders the preview of what the recogniser will see; see {@link #showPreview}. */
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();

    private ImageView imagePreview;
    private ProgressBar progress;
    private TextView ocrStats;
    private TextView ocrOutput;
    private MaterialSwitch enhanceSwitch;
    private LlmIngredientReader ingredientReader;

    /** Incremented per image so a slow, superseded OCR or model result is ignored. */
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
        ocrStats = findViewById(R.id.ocr_stats);
        ocrOutput = findViewById(R.id.ocr_output);
        enhanceSwitch = findViewById(R.id.enhance_switch);

        // Swap PassThroughFilter for new IngredientListFilter() (same package) to restrict output
        // to the ingredients declaration — nothing else in this class needs to change.
        rebuildPipeline();
        enhanceSwitch.setOnCheckedChangeListener((button, checked) -> rebuildPipeline());
        ingredientReader = new LlmIngredientReader(this);

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

        findViewById(R.id.pick_image_button).setOnClickListener(v ->
                pickImage.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
    }

    /** Points the pipeline at whichever preparer the switch currently selects. */
    private void rebuildPipeline() {
        if (ocrPipeline != null) {
            ocrPipeline.close();
        }
        ocrPipeline = new OcrPipeline(new PassThroughFilter(), currentPreparer());
    }

    private ImagePreparer currentPreparer() {
        return enhanceSwitch.isChecked() ? new LabelImagePreparer() : new PassThroughPreparer();
    }

    private void runOcr(@NonNull Uri imageUri) {
        showPreview(imageUri);
        setBusy(true);
        ocrStats.setVisibility(View.GONE);
        ocrOutput.setText("");

        // OCR and the model both answer asynchronously; results for an image the user has since
        // replaced must not overwrite the newer one.
        final int thisRequest = ++requestId;

        long startedAt = SystemClock.elapsedRealtime();
        ocrPipeline.recognize(this, imageUri, new OcrPipeline.Callback() {
            @Override
            public void onSuccess(@NonNull OcrResult result) {
                if (thisRequest != requestId) {
                    return;
                }
                // The stats describe recognition alone, so they are shown before the model runs.
                showStats(result, SystemClock.elapsedRealtime() - startedAt);
                // "Found no text" and "could not read the image" are shown differently on purpose:
                // neither may be presented as a clean, allergen-free read.
                if (result.isEmpty()) {
                    setBusy(false);
                    ocrOutput.setText(R.string.ocr_empty_result);
                } else {
                    readIngredients(result.fullText(), thisRequest);
                }
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (thisRequest != requestId) {
                    return;
                }
                setBusy(false);
                ocrOutput.setText(getString(R.string.ocr_error, messageOf(error)));
            }
        });
    }

    /** Hands the OCR text to the on-device model, which corrects misreads and lists the ingredients. */
    private void readIngredients(String ocrText, int thisRequest) {
        ocrOutput.setText(R.string.reading_ingredients);
        ingredientReader.read(ocrText, new LlmIngredientReader.Callback() {
            @Override
            public void onSuccess(@NonNull List<String> ingredients) {
                if (thisRequest != requestId) {
                    return;
                }
                setBusy(false);
                ocrOutput.setText(formatIngredients(ingredients, ocrText));
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (thisRequest != requestId) {
                    return;
                }
                setBusy(false);
                if (error instanceof LlmIngredientReader.ModelNotFoundException) {
                    ocrOutput.setText(getString(R.string.model_missing,
                            ((LlmIngredientReader.ModelNotFoundException) error).directory().getAbsolutePath()));
                } else {
                    ocrOutput.setText(getString(R.string.ingredients_error, messageOf(error)));
                }
            }
        });
    }

    /**
     * The ingredients as a bulleted list. When the model found no ingredient list, says so and
     * shows the raw text instead, so it is clear what was actually read.
     */
    private String formatIngredients(List<String> ingredients, String ocrText) {
        if (ingredients.isEmpty()) {
            return getString(R.string.no_ingredients_found, ocrText);
        }
        StringBuilder out = new StringBuilder(getString(R.string.ingredients_found, ingredients.size()));
        for (String ingredient : ingredients) {
            out.append("\n• ").append(ingredient);
        }
        return out.toString();
    }

    private static String messageOf(Exception error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    /**
     * Shows the spinner and locks the switch while a run is in flight — recognition and the model
     * stage that follows it. Rebuilding the pipeline mid-recognition would close the one we are
     * waiting on, and the callback would never arrive.
     */
    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        enhanceSwitch.setEnabled(!busy);
    }

    /** Lines, characters and elapsed time — enough to tell whether a change helped. */
    private void showStats(@NonNull OcrResult result, long elapsedMillis) {
        ocrStats.setText(getString(R.string.ocr_stats,
                result.lines().size(), result.fullText().length(), elapsedMillis));
        ocrStats.setVisibility(View.VISIBLE);
    }

    /**
     * Shows the image as the recogniser will see it, running the same decode and preparation off the
     * main thread.
     *
     * <p>This duplicates work the pipeline is about to do, which would be the wrong trade in the real
     * capture screen — but widening {@link OcrPipeline}'s API to hand back a debug bitmap would be
     * worse, and this harness is slated for deletion either way.
     */
    private void showPreview(@NonNull Uri imageUri) {
        imagePreview.setVisibility(View.VISIBLE);

        ImagePreparer preparer = currentPreparer();
        if (preparer instanceof PassThroughPreparer) {
            imagePreview.setImageURI(imageUri);
            return;
        }

        // Nothing to show until the prepared bitmap arrives, and decoding the original here as a
        // placeholder would put the main-thread decode back that the pipeline just moved off it.
        imagePreview.setImageDrawable(null);
        previewExecutor.execute(() -> {
            final Bitmap prepared;
            try {
                prepared = preparer.prepare(LabelImageDecoder.decode(this, imageUri));
            } catch (Exception | OutOfMemoryError ignored) {
                // The preview is incidental; the pipeline reports the real failure through onFailure.
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) {
                    prepared.recycle();
                    return;
                }
                imagePreview.setImageBitmap(prepared);
            });
        });
    }

    @Override
    protected void onDestroy() {
        requestId++;
        previewExecutor.shutdown();
        ocrPipeline.close();
        ingredientReader.close();
        super.onDestroy();
    }
}
