package com.example.allergycheck.ingredients;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the ingredient list out of OCR text with a small on-device language model (Gemma, run by
 * MediaPipe LLM Inference). Fully offline: the text never leaves the phone.
 *
 * <p>The model file is too large to ship inside the APK, so it is loaded from the app's own
 * external files folder — see {@link #modelDirectory(Context)}. Push it there once with:
 * <pre>adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.example.allergycheck/files/llm/</pre>
 *
 * <p>Create one per screen and {@link #close()} it in {@code onDestroy}. All model work happens on
 * a single background thread; callbacks arrive on the main thread and stop after {@code close()}.
 */
public final class LlmIngredientReader {

    /** Outcome of one read. Both methods are called on the main thread. */
    public interface Callback {

        /** An empty list means the model found no ingredient list in the text. */
        @MainThread
        void onSuccess(@NonNull List<String> ingredients);

        /** The model is missing, failed to run, or replied off script. Never shown as "no ingredients". */
        @MainThread
        void onFailure(@NonNull Exception error);
    }

    /** Thrown when no model file has been put on the device yet. */
    public static final class ModelNotFoundException extends Exception {
        private final File directory;

        ModelNotFoundException(File directory) {
            super("No model file (.task) found in " + directory.getAbsolutePath());
            this.directory = directory;
        }

        /** Where the model file should be copied. */
        public File directory() {
            return directory;
        }
    }

    /** Preferred model file name; any other single .task file in the folder is used if this is absent. */
    public static final String MODEL_FILE = "gemma3-1b-it-int4.task";

    /** Prompt + reply budget. Must not exceed the model build's context size (2048 for the 1B q4 build). */
    private static final int MAX_TOKENS = 2048;

    /** Tokens kept free for the reply; a long ingredient list fits comfortably. */
    private static final int REPLY_TOKENS = 512;

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    /** Created lazily on the worker thread (loading takes a few seconds) and only touched there. */
    private LlmInference engine;

    public LlmIngredientReader(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** The folder the model file is loaded from: {@code <external files>/llm}. */
    @NonNull
    public static File modelDirectory(@NonNull Context context) {
        File base = context.getExternalFilesDir(null);
        return new File(base != null ? base : context.getFilesDir(), "llm");
    }

    /** Asks the model for the ingredients in {@code ocrText}. */
    @MainThread
    public void read(@NonNull String ocrText, @NonNull Callback callback) {
        worker.execute(() -> {
            try {
                List<String> ingredients = IngredientPrompt.parse(generate(ocrText));
                deliver(() -> callback.onSuccess(ingredients));
            } catch (Exception e) {
                deliver(() -> callback.onFailure(e));
            } catch (OutOfMemoryError e) {
                deliver(() -> callback.onFailure(new Exception("Not enough memory to run the model", e)));
            }
        });
    }

    /** Releases the model. Safe to call more than once; no callbacks arrive afterwards. */
    @MainThread
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Runs after any request already in progress, so the engine is never closed mid-generation.
        worker.execute(() -> {
            if (engine != null) {
                engine.close();
                engine = null;
            }
        });
        worker.shutdown();
    }

    // ---- worker thread ----------------------------------------------------------------------

    private String generate(String ocrText) throws Exception {
        LlmInference llm = engine();
        String prompt = fitToContext(llm, ocrText);

        LlmInferenceSession.LlmInferenceSessionOptions options =
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(1)          // greedy: the same label always gives the same list
                        .setRandomSeed(0)
                        .setPromptTemplates(gemmaTemplates())
                        .build();
        LlmInferenceSession session = LlmInferenceSession.createFromOptions(llm, options);
        try {
            session.addQueryChunk(prompt);
            return session.generateResponse();
        } finally {
            session.close();
        }
    }

    /** Shortens the label text if the prompt would leave too little room for the reply. */
    private static String fitToContext(LlmInference llm, String ocrText) {
        String prompt = IngredientPrompt.build(ocrText);
        int budget = MAX_TOKENS - REPLY_TOKENS;
        int size = llm.sizeInTokens(prompt);
        if (size <= budget) {
            return prompt;
        }
        String text = ocrText.trim();
        int keep = (int) (Math.min(text.length(), IngredientPrompt.MAX_OCR_CHARS) * (budget / (double) size) * 0.9);
        return IngredientPrompt.build(text.substring(0, Math.max(0, keep)));
    }

    private LlmInference engine() throws ModelNotFoundException {
        if (engine == null) {
            LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(findModel().getAbsolutePath())
                    .setMaxTokens(MAX_TOKENS)
                    .build();
            engine = LlmInference.createFromOptions(appContext, options);
        }
        return engine;
    }

    private File findModel() throws ModelNotFoundException {
        File dir = modelDirectory(appContext);
        File preferred = new File(dir, MODEL_FILE);
        if (preferred.isFile()) {
            return preferred;
        }
        File[] tasks = dir.listFiles((d, name) -> name.endsWith(".task"));
        if (tasks != null && tasks.length > 0) {
            return tasks[0];
        }
        throw new ModelNotFoundException(dir);
    }

    /** Gemma's chat turn markers, set explicitly so the prompt is wrapped exactly once. */
    private static PromptTemplates gemmaTemplates() {
        return PromptTemplates.builder()
                .setUserPrefix("<start_of_turn>user\n")
                .setUserSuffix("<end_of_turn>\n")
                .setModelPrefix("<start_of_turn>model\n")
                .setModelSuffix("<end_of_turn>\n")
                .build();
    }

    private void deliver(Runnable toMain) {
        mainHandler.post(() -> {
            if (!closed) {
                toMain.run();
            }
        });
    }
}
