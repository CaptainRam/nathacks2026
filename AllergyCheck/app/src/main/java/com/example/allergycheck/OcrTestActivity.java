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

import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.example.allergycheck.ocr.PassThroughFilter;

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

        ocrPipeline.recognize(this, imageUri, new OcrPipeline.Callback() {
            @Override
            public void onSuccess(@NonNull OcrResult result) {
                progress.setVisibility(View.GONE);
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
                progress.setVisibility(View.GONE);
                String message = error.getMessage() != null
                        ? error.getMessage()
                        : error.getClass().getSimpleName();
                ocrOutput.setText(getString(R.string.ocr_error, message));
            }
        });
    }

    @Override
    protected void onDestroy() {
        ocrPipeline.close();
        super.onDestroy();
    }
}
