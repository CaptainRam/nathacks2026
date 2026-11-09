package com.example.allergycheck;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.example.allergycheck.ingredients.LlmIngredientReader;
import com.example.allergycheck.ingredients.LocalModelManager;
import com.example.allergycheck.ocr.ImagePreparer;
import com.example.allergycheck.ocr.LabelImageDecoder;
import com.example.allergycheck.ocr.LabelImagePreparer;
import com.example.allergycheck.ocr.OcrPipeline;
import com.example.allergycheck.ocr.OcrResult;
import com.example.allergycheck.ocr.PassThroughFilter;
import com.example.allergycheck.ocr.PassThroughPreparer;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.ArrayList;
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
    private static final String STATE_IMAGE = "ocr_image";
    private static final String STATE_ENHANCE = "ocr_enhance";
    private static final String STATE_BUSY = "ocr_busy";
    private static final String STATE_PENDING_TEXT = "ocr_pending_text";
    private static final String STATE_MODEL_GENERATION = "ocr_model_generation";

    private OcrPipeline ocrPipeline;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;

    /** Renders the preview of what the recogniser will see; see {@link #showPreview}. */
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();

    private ImageView imagePreview;
    private CircularProgressIndicator progress;
    private TextView ocrStats;
    private TextView ocrOutput;
    private TextView ocrStatus;
    private MaterialSwitch enhanceSwitch;
    private LlmIngredientReader ingredientReader;
    private ModelReadState modelReads;
    @Nullable private LlmIngredientReader.Callback modelCallback;
    private int modelGeneration;
    private ModelDownloadController modelDownloadController;
    private String pendingOcrText;
    private int pendingModelRequest;
    @Nullable private Uri selectedImage;
    private boolean busy;
    private boolean resumeOcrOnStart;

    /** Incremented per image so a slow, superseded OCR or model result is ignored. */
    private int requestId;

    /**
     * Native generation cannot be interrupted by close(). Keep its reader and answer through
     * recreation so rotation neither starts a second engine nor retains a destroyed screen.
     */
    public static final class ModelReadState extends AndroidViewModel {
        private final LlmIngredientReader reader;
        private int generation;
        @Nullable private String input;
        @Nullable private List<String> ingredients;
        @Nullable private Exception error;
        private boolean completed;
        @Nullable private LlmIngredientReader.Callback observer;

        public ModelReadState(@NonNull Application application) {
            super(application);
            reader = new LlmIngredientReader(application);
        }

        int start(String text) {
            int current = ++generation;
            input = text;
            ingredients = null;
            error = null;
            completed = false;
            observer = null;
            reader.read(text, new LlmIngredientReader.Callback() {
                @Override public void onSuccess(@NonNull List<String> result) {
                    complete(current, result, null);
                }

                @Override public void onFailure(@NonNull Exception failure) {
                    complete(current, null, failure);
                }
            });
            return current;
        }

        boolean isCurrent(int candidate) {
            return candidate > 0 && candidate == generation && input != null;
        }

        void observe(int candidate, LlmIngredientReader.Callback callback) {
            if (isCurrent(candidate)) {
                observer = callback;
                deliver();
            }
        }

        void detach(@Nullable LlmIngredientReader.Callback callback) {
            if (observer == callback) observer = null;
        }

        private void complete(int current, @Nullable List<String> result,
                              @Nullable Exception failure) {
            if (current != generation) return;
            ingredients = result == null ? null : new ArrayList<>(result);
            error = failure;
            completed = true;
            deliver();
        }

        private void deliver() {
            LlmIngredientReader.Callback callback = observer;
            if (completed && callback != null) {
                if (error != null) callback.onFailure(error);
                else if (ingredients != null) callback.onSuccess(ingredients);
            }
        }

        @Override protected void onCleared() {
            reader.close();
        }
    }

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
        ocrStats = findViewById(R.id.ocr_stats);
        ocrOutput = findViewById(R.id.ocr_output);
        ocrStatus = findViewById(R.id.ocr_status);
        enhanceSwitch = findViewById(R.id.enhance_switch);
        // Restore this before building either preparer. The view hierarchy must not later
        // restore the switch and close a pipeline whose resumed request is already in flight.
        enhanceSwitch.setSaveEnabled(false);
        if (savedInstanceState != null) {
            enhanceSwitch.setChecked(savedInstanceState.getBoolean(STATE_ENHANCE, true));
        }

        // Swap PassThroughFilter for new IngredientListFilter() (same package) to restrict output
        // to the ingredients declaration — nothing else in this class needs to change.
        rebuildPipeline();
        enhanceSwitch.setOnCheckedChangeListener((button, checked) -> rebuildPipeline());
        modelReads = new ViewModelProvider(this).get(ModelReadState.class);
        ingredientReader = modelReads.reader;
        // The OCR harness ignores allergen changes, so it needs no result back from Settings.
        modelDownloadController = new ModelDownloadController(this, () -> startActivity(
                new Intent(this, SettingsActivity.class)
                        .putExtra(SettingsActivity.EXTRA_SHOW_MODEL, true)));

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

        if (savedInstanceState != null) {
            restoreText(savedInstanceState, "output", ocrOutput);
            restoreText(savedInstanceState, "status", ocrStatus);
            restoreText(savedInstanceState, "stats", ocrStats);
            String image = savedInstanceState.getString(STATE_IMAGE);
            selectedImage = image == null ? null : Uri.parse(image);
            pendingOcrText = savedInstanceState.getString(STATE_PENDING_TEXT);
            boolean restoringBusy = savedInstanceState.getBoolean(STATE_BUSY) && selectedImage != null;
            int retained = savedInstanceState.getInt(STATE_MODEL_GENERATION);
            if (restoringBusy && modelReads.isCurrent(retained)) modelGeneration = retained;
            // Only the cheap OCR stage is restarted. Native model work is retained above; after
            // process death its ViewModel is gone, so rerunning the photo is the safe fallback.
            resumeOcrOnStart = restoringBusy && modelGeneration == 0;
            setBusy(restoringBusy);
            if (selectedImage != null && !resumeOcrOnStart) {
                // Completed results only need their preview reloaded, not a second model run.
                showPreview(selectedImage, ++requestId);
            }
            pendingModelRequest = requestId;
        }
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
        // Preview, OCR and model callbacks all belong to the same selected-image request.
        final int thisRequest = ++requestId;
        selectedImage = imageUri;
        resumeOcrOnStart = false;
        clearModelObservation();
        pendingOcrText = null;
        showPreview(imageUri, thisRequest);
        setBusy(true);
        ocrStats.setVisibility(View.GONE);
        ocrOutput.setText("");
        showStatus(R.string.ocr_reading_label);

        // OCR and the model both answer asynchronously; results for an image the user has since
        // replaced must not overwrite the newer one.
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
                    showStatus(R.string.ocr_output_empty);
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
                showStatus(R.string.ocr_output_failed);
            }
        });
    }

    /** Hands the OCR text to the on-device model, which corrects misreads and lists the ingredients. */
    private void readIngredients(String ocrText, int thisRequest) {
        if (!LocalModelManager.get(this).getStatus().isInstalled()) {
            pendingOcrText = ocrText;
            pendingModelRequest = thisRequest;
            setBusy(false);
            ocrOutput.setText(R.string.model_download_unavailable);
            showStatus(R.string.ocr_waiting_for_model);
            return;
        }
        setBusy(true);
        ocrOutput.setText(R.string.reading_ingredients);
        showStatus(R.string.reading_ingredients);
        modelGeneration = modelReads.start(ocrText);
        observeModelRead(modelGeneration, thisRequest);
    }

    private void observeModelRead(int generation, int thisRequest) {
        String ocrText = modelReads.input;
        modelReads.detach(modelCallback);
        modelCallback = new LlmIngredientReader.Callback() {
            @Override
            public void onSuccess(@NonNull List<String> ingredients) {
                if (!isCurrentModelRead(generation, thisRequest)) return;
                clearModelObservation();
                setBusy(false);
                ocrOutput.setText(formatIngredients(ingredients, ocrText));
                showStatus(ingredients.isEmpty()
                        ? R.string.ocr_output_no_ingredients : R.string.ocr_output_ready);
            }

            @Override
            public void onFailure(@NonNull Exception error) {
                if (!isCurrentModelRead(generation, thisRequest)) return;
                clearModelObservation();
                setBusy(false);
                if (error instanceof LlmIngredientReader.ModelNotFoundException) {
                    pendingOcrText = ocrText;
                    pendingModelRequest = thisRequest;
                    ocrOutput.setText(R.string.model_download_unavailable);
                    showStatus(R.string.ocr_waiting_for_model);
                    LocalModelManager.get(OcrTestActivity.this).refresh();
                } else {
                    ocrOutput.setText(getString(R.string.ingredients_error, messageOf(error)));
                    showStatus(R.string.ocr_output_failed);
                }
            }
        };
        modelReads.observe(generation, modelCallback);
    }

    private boolean isCurrentModelRead(int generation, int request) {
        return generation == modelGeneration && request == requestId
                && !isDestroyed() && !isFinishing();
    }

    private void clearModelObservation() {
        modelReads.detach(modelCallback);
        modelCallback = null;
        modelGeneration = 0;
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
     * Shows the progress indicator and locks the switch while a run is in flight — recognition and the model
     * stage that follows it. Rebuilding the pipeline mid-recognition would close the one we are
     * waiting on, and the callback would never arrive.
     */
    private void setBusy(boolean busy) {
        this.busy = busy;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        enhanceSwitch.setEnabled(!busy);
    }

    private void showStatus(int message) {
        ocrStatus.setText(message);
        ocrStatus.setVisibility(View.VISIBLE);
    }

    /** Lines, characters and elapsed time — enough to tell whether a change helped. */
    private void showStats(@NonNull OcrResult result, long elapsedMillis) {
        int lines = result.lines().size();
        int characters = result.fullText().length();
        ocrStats.setText(getString(R.string.ocr_stats,
                lines, characters, elapsedMillis));
        ocrStats.setContentDescription(getString(R.string.ocr_stats_accessibility,
                getResources().getQuantityString(R.plurals.ocr_stats_lines, lines, lines),
                getResources().getQuantityString(R.plurals.ocr_stats_characters,
                        characters, characters),
                getResources().getQuantityString(R.plurals.ocr_stats_milliseconds,
                        (int) Math.min(elapsedMillis, Integer.MAX_VALUE), elapsedMillis)));
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
    private void showPreview(@NonNull Uri imageUri, int thisRequest) {
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
                if (thisRequest != requestId || isDestroyed() || isFinishing()) {
                    prepared.recycle();
                    return;
                }
                imagePreview.setImageBitmap(prepared);
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        modelDownloadController.start();
        if (resumeOcrOnStart && selectedImage != null) {
            // The old instance closes its OCR pipeline and invalidates its callbacks. Restart
            // that stage once; a new picker result also clears this flag in runOcr.
            runOcr(selectedImage);
        } else if (modelGeneration != 0) {
            observeModelRead(modelGeneration, requestId);
        }
        LocalModelManager.get(this).addListener(modelListener);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString(STATE_IMAGE, selectedImage == null ? null : selectedImage.toString());
        state.putBoolean(STATE_ENHANCE, enhanceSwitch.isChecked());
        state.putBoolean(STATE_BUSY, busy);
        state.putString(STATE_PENDING_TEXT, pendingOcrText);
        state.putInt(STATE_MODEL_GENERATION, modelGeneration);
        saveText(state, "output", ocrOutput);
        saveText(state, "status", ocrStatus);
        saveText(state, "stats", ocrStats);
        super.onSaveInstanceState(state);
    }

    private static void saveText(Bundle state, String key, TextView view) {
        state.putString(key + "_text", view.getText().toString());
        CharSequence description = view.getContentDescription();
        state.putString(key + "_description", description == null ? null : description.toString());
        state.putInt(key + "_visibility", view.getVisibility());
    }

    private static void restoreText(Bundle state, String key, TextView view) {
        if (!state.containsKey(key + "_text")) return;
        view.setText(state.getString(key + "_text"));
        view.setContentDescription(state.getString(key + "_description"));
        view.setVisibility(state.getInt(key + "_visibility", view.getVisibility()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        modelDownloadController.offerIntroduction();
    }

    @Override
    protected void onStop() {
        modelReads.detach(modelCallback);
        LocalModelManager.get(this).removeListener(modelListener);
        modelDownloadController.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        requestId++;
        previewExecutor.shutdown();
        ocrPipeline.close();
        modelReads.detach(modelCallback);
        super.onDestroy();
    }
}
