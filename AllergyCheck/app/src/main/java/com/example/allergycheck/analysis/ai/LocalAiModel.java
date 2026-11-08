package com.example.allergycheck.analysis.ai;

import android.content.Context;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.ingredients.LocalModelManager;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates;

import java.io.File;
import java.io.IOException;

/** Uses the downloaded Gemma task offline, with a new session for each ingredient/allergen pair. */
public final class LocalAiModel implements AiModel {
    private static final int MAX_TOKENS = 2048;
    private static final int REPLY_TOKENS = 128;
    private final Context context;
    private LlmInference engine;
    private LlmInferenceSession.LlmInferenceSessionOptions sessionOptions;
    private boolean closed;

    public LocalAiModel(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
        if (closed) throw new IllegalStateException("The local model has been closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String prompt = AllergenPrompt.local(ingredient, allergen);
        try {
            LlmInference llm = engine();
            if (llm.sizeInTokens(prompt) > MAX_TOKENS - REPLY_TOKENS) {
                throw new IOException("This ingredient is too long for the local model");
            }
            LlmInferenceSession session = LlmInferenceSession.createFromOptions(llm, sessionOptions());
            try {
                session.addQueryChunk(prompt);
                String reply = session.generateResponse();
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                return AllergenPrompt.parse(reply);
            } finally {
                session.close();
            }
        } catch (LinkageError error) {
            throw new IOException("The local model cannot run on this device", error);
        } catch (OutOfMemoryError error) {
            throw new IOException("Not enough memory to run the local model", error);
        }
    }

    /** Immutable and identical for every pair, so it is built once per model rather than per call. */
    private LlmInferenceSession.LlmInferenceSessionOptions sessionOptions() {
        if (sessionOptions == null) {
            sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(1)
                    .setRandomSeed(0)
                    .setPromptTemplates(PromptTemplates.builder()
                            .setUserPrefix("<start_of_turn>user\n")
                            .setUserSuffix("<end_of_turn>\n")
                            .setModelPrefix("<start_of_turn>model\n")
                            .setModelSuffix("<end_of_turn>\n")
                            .build())
                    .build();
        }
        return sessionOptions;
    }

    private LlmInference engine() throws IOException {
        if (engine == null) {
            File model = LocalModelManager.installedModel(context);
            if (model == null) throw new IOException("Download the local model in Settings first");
            engine = LlmInference.createFromOptions(context,
                    LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(model.getAbsolutePath())
                            .setMaxTokens(MAX_TOKENS)
                            .build());
        }
        return engine;
    }

    @Override
    public void close() {
        closed = true;
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }
}
