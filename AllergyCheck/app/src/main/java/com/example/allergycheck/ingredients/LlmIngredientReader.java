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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the ingredient list out of OCR text with a small on-device language model (Gemma, run by
 * MediaPipe LLM Inference). Fully offline: the text never leaves the phone.
 *
 * <p>The model file is too large to ship inside the APK, so it is loaded from the app's own
 * external files folder — see {@link #modelDirectory(Context)}. Download it from Settings, or use:
 * <pre>adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.example.allergycheck/files/llm/</pre>
 *
 * <p>Create one per screen and {@link #close()} it in {@code onDestroy}. All model work happens on
 * a single background thread; callbacks arrive on the main thread and stop after {@code close()},
 * and every read either succeeds, fails, or times out — see {@link #read}.
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
    public static final String MODEL_FILE = ModelFiles.MODEL_FILE;

    /** Prompt + reply budget. Must not exceed the model build's context size (2048 for the 1B q4 build). */
    private static final int MAX_TOKENS = 2048;

    /** Tokens kept free for the reply; a long ingredient list fits comfortably. */
    private static final int REPLY_TOKENS = 512;

    /**
     * How long a caller waits for one read before it is told the model did not answer. Loading the
     * model takes a few seconds on a cold start and generation a few more, so this is generous; it
     * exists only so a wedged generation cannot leave the screen waiting for ever.
     */
    private static final long READ_TIMEOUT_MS = 90_000L;

    private final Context appContext;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean closed;

    /** Created lazily on the worker thread (loading takes a few seconds) and only touched there. */
    private LlmInference engine;
    private File loadedModel;
    private long loadedModelModified;
    private long loadedModelLength;

    public LlmIngredientReader(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** The folder the model file is loaded from: {@code <external files>/llm}. */
    @NonNull
    public static File modelDirectory(@NonNull Context context) {
        return LocalModelManager.modelDirectory(context);
    }

    /**
     * Asks the model for the ingredients in {@code ocrText}.
     *
     * <p>Exactly one callback arrives, on the main thread, unless the reader is closed first. After
     * {@link #close()} this does nothing at all: the worker is shut down, and the close contract
     * promises no callbacks, so a late OCR or UI callback must neither crash nor report anything.
     *
     * <p>If the model has not answered within {@link #READ_TIMEOUT_MS} the caller is released with
     * {@code onFailure}. That is a bound on <em>waiting</em> only: MediaPipe's {@code
     * generateResponse()} is a blocking native call that cannot be interrupted, so the generation
     * keeps running on the worker thread until it finishes on its own, and its late result is
     * dropped. Requests queued behind it still wait for it.
     */
    @MainThread
    public void read(@NonNull String ocrText, @NonNull Callback callback) {
        if (closed) {
            return;
        }
        AtomicBoolean answered = new AtomicBoolean();
        Runnable timeout = () -> {
            if (!closed && answered.compareAndSet(false, true)) {
                callback.onFailure(new Exception("The model took too long to read the label"));
            }
        };
        mainHandler.postDelayed(timeout, READ_TIMEOUT_MS);
        try {
            worker.execute(() -> {
                try {
                    List<String> ingredients = IngredientPrompt.parse(generate(ocrText));
                    deliver(answered, timeout, () -> callback.onSuccess(ingredients));
                } catch (Throwable error) {
                    // Throwable, not Exception: a missing libllm_inference_engine_jni.so for this
                    // device's ABI arrives as UnsatisfiedLinkError, and a failed native init as
                    // NoClassDefFoundError / ExceptionInInitializerError. Escaping the Runnable
                    // would leave the screen stuck on "Reading ingredients" with no error at all.
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    deliver(answered, timeout, () -> callback.onFailure(asException(error)));
                }
            });
        } catch (RejectedExecutionException rejected) {
            // The worker refused the task (a race with close(), or no thread available).
            deliver(answered, timeout,
                    () -> callback.onFailure(new Exception("The ingredient reader is not running", rejected)));
        }
    }

    /**
     * Releases the model. Safe to call more than once; no callbacks arrive afterwards, and later
     * {@link #read} calls are ignored.
     *
     * <p>Never blocks the main thread: the release is queued on the worker and this returns at once.
     * It therefore runs after any request already in progress, so the engine is never closed
     * mid-generation — but a generation that has wedged in native code also keeps the model's memory
     * alive until it returns, which nothing here can shorten.
     */
    @MainThread
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Runs after any request already in progress, so the engine is never closed mid-generation.
        try {
            worker.execute(() -> {
                if (engine != null) {
                    engine.close();
                    engine = null;
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Already shut down: there is nothing left to release on the worker.
        }
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
        File model = findModel();
        if (engine != null && (!model.equals(loadedModel)
                || model.lastModified() != loadedModelModified || model.length() != loadedModelLength)) {
            engine.close();
            engine = null;
        }
        if (engine == null) {
            LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.getAbsolutePath())
                    .setMaxTokens(MAX_TOKENS)
                    .build();
            engine = LlmInference.createFromOptions(appContext, options);
            loadedModel = model;
            loadedModelModified = model.lastModified();
            loadedModelLength = model.length();
        }
        return engine;
    }

    private File findModel() throws ModelNotFoundException {
        File model = LocalModelManager.installedModel(appContext);
        if (model != null) {
            return model;
        }
        if (engine != null) {
            engine.close();
            engine = null;
        }
        throw new ModelNotFoundException(modelDirectory(appContext));
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

    /**
     * Hands one read's outcome to the main thread, unless the reader has been closed or the read was
     * already answered by its timeout. {@code answered} is what makes exactly one callback arrive;
     * the pending {@code timeout} is cancelled on the main thread, where it was posted.
     */
    private void deliver(AtomicBoolean answered, Runnable timeout, Runnable toMain) {
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(timeout);
            if (!closed && answered.compareAndSet(false, true)) {
                toMain.run();
            }
        });
    }

    /**
     * Presents any failure as an {@link Exception}, because {@link Callback#onFailure} reports one.
     * Native model loading fails with {@link Error}s rather than exceptions, and those must still
     * reach the screen as an error instead of silently ending the request.
     */
    private static Exception asException(Throwable error) {
        if (error instanceof Exception) {
            return (Exception) error;
        }
        if (error instanceof OutOfMemoryError) {
            return new Exception("Not enough memory to run the model", error);
        }
        return new Exception("The model could not be run on this device", error);
    }
}
