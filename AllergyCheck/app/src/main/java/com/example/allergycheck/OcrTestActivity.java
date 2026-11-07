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

import com.example.allergycheck.ingredients.LlmIngredientReader;
import com.example.allergycheck.ingredients.LocalModelManager;
import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.example.allergycheck.ocr.PassThroughFilter;

import java.util.List;

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
    private LlmIngredientReader ingredientReader;
    private ModelDownloadController modelDownloadController;
    private String pendingOcrText;
    private int pendingModelRequest;

    /** Incremented per image so a slow, superseded OCR or model result is ignored. */
    private int requestId;

    private final LocalModelManager.Listener modelListener = status -> {
        if (status.isInstalled() && pendingOcrText != null && pendingModelRequest == requestId) {
            String text = pendingOcrText;
            pendingOcrText = null;
            readIngredients(text, requestId);
        }
    };

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
        ingredientReader = new LlmIngredientReader(this);
        modelDownloadController = new ModelDownloadController(this);

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

    private void runOcr(@NonNull Uri imageUri) {
        pendingOcrText = null;
        imagePreview.setImageURI(imageUri);
        imagePreview.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        ocrOutput.setText("");

        // OCR and the model both answer asynchronously; results for an image the user has since
        // replaced must not overwrite the newer one.
        final int thisRequest = ++requestId;

        ocrPipeline.recognize(this, imageUri, new OcrPipeline.Callback() {
            @Override
            public void onSuccess(@NonNull OcrResult result) {
                if (thisRequest != requestId) {
                    return;
                }
                // "Found no text" and "could not read the image" are shown differently on purpose:
                // neither may be presented as a clean, allergen-free read.
                if (result.isEmpty()) {
                    progress.setVisibility(View.GONE);
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
                progress.setVisibility(View.GONE);
                ocrOutput.setText(getString(R.string.ocr_error, messageOf(error)));
            }
        });
    }

    /** Hands the OCR text to the on-device model, which corrects misreads and lists the ingredients. */
    private void readIngredients(String ocrText, int thisRequest) {
        if (!LocalModelManager.get(this).getStatus().isInstalled()) {
            pendingOcrText = ocrText;
            pendingModelRequest = thisRequest;
            progress.setVisibility(View.GONE);
            ocrOutput.setText(R.string.model_download_unavailable);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        ocrOutput.setText(R.string.reading_ingredients);
        ingredientReader.read(ocrText, new LlmIngredientReader.Callback() {
            @Override
            public void onSuccess(@NonNull List<String> ingredients) {
                if (thisRequest != requestId) {
                    return;
                }
                progress.setVisibility(View.GONE);
                ocrOutput.setText(formatIngredients(ingredients, ocrText));
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (thisRequest != requestId) {
                    return;
                }
                progress.setVisibility(View.GONE);
                if (error instanceof LlmIngredientReader.ModelNotFoundException) {
                    pendingOcrText = ocrText;
                    pendingModelRequest = thisRequest;
                    LocalModelManager.get(OcrTestActivity.this).refresh();
                    ocrOutput.setText(R.string.model_download_unavailable);
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

    @Override
    protected void onStart() {
        super.onStart();
        modelDownloadController.start();
        LocalModelManager.get(this).addListener(modelListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        modelDownloadController.offerIntroduction();
    }

    @Override
    protected void onStop() {
        LocalModelManager.get(this).removeListener(modelListener);
        modelDownloadController.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        requestId++;
        ocrPipeline.close();
        ingredientReader.close();
        super.onDestroy();
    }
}
