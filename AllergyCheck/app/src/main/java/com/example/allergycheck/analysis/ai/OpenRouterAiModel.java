package com.example.allergycheck.analysis.ai;

import com.example.allergycheck.analysis.Allergen;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** OpenRouter full-list LLM and single-ingredient Decisions, always one allergen per request. */
public final class OpenRouterAiModel implements AiModel {
    private static final String CHAT_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String DECISIONS_URL = "https://openrouter.ai/api/alpha/decisions";
    private static final String QUESTION_ID = "allergen_risk";
    private static final int MAX_RESPONSE_BYTES = 262_144;
    private final String apiKey;
    private final String modelId;
    private final Double threshold;
    private final ConnectionFactory connections;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean closed;

    /** A failure whose status can tell the caller to stop rather than repeat a broken configuration. */
    public static final class ApiException extends IOException {
        private final int status;

        ApiException(int status) {
            super(status == 401 ? "OpenRouter rejected the API key"
                    : status == 402 ? "OpenRouter credits or quota are exhausted"
                    : status == 429 ? "OpenRouter rate limit reached; try again later"
                    : "OpenRouter request failed (HTTP " + status + ")");
            this.status = status;
        }

        public int status() { return status; }

        /**
         * Only account-wide problems: a bad key, no credits, or an unknown model. OpenRouter also
         * sends 400 (e.g. context too long) and 403 (moderation) for a single input, which must not
         * stop the other allergens; a truly broken setup still hits the consecutive-failure limit.
         */
        public boolean permanent() {
            return status == 401 || status == 402 || status == 404;
        }
    }

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    public OpenRouterAiModel(String apiKey, String modelId) {
        this(apiKey, modelId, null, url -> (HttpURLConnection) url.openConnection());
    }

    /** Native Decisions endpoint, using a two-level Score question in [0, 1]. */
    public OpenRouterAiModel(String apiKey, String modelId, double threshold) {
        this(apiKey, modelId, threshold, url -> (HttpURLConnection) url.openConnection());
    }

    OpenRouterAiModel(String apiKey, String modelId, Double threshold, ConnectionFactory connections) {
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("Add an OpenRouter API key in Settings");
        }
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("Choose an OpenRouter model in Settings");
        }
        if (threshold != null && (!Double.isFinite(threshold) || threshold < 0 || threshold > 1)) {
            throw new IllegalArgumentException("The decision threshold must be between 0 and 1");
        }
        this.apiKey = apiKey.trim();
        this.modelId = modelId.trim();
        this.threshold = threshold;
        this.connections = connections;
    }

    @Override
    public AiDecision evaluate(String ingredient, Allergen allergen) throws Exception {
        if (threshold == null) {
            AiDecision decision = evaluateBatch(Collections.singletonList(ingredient), allergen).get(0);
            if (decision == null) {
                throw new AiResponseFormatException("The model omitted the ingredient result");
            }
            return decision;
        }
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String response = post(DECISIONS_URL, decisionsRequest(ingredient, allergen).toString());
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONObject root = new JSONObject(response);
        if (root.has("error")) throw new IOException("OpenRouter could not complete the request");
        return parseDecisions(root, threshold);
    }

    @Override
    public boolean supportsBatch() { return threshold == null; }

    @Override
    public List<AiDecision> evaluateBatch(List<String> ingredients, Allergen allergen) throws Exception {
        if (threshold != null) {
            throw new UnsupportedOperationException("Decisions evaluates one ingredient per request");
        }
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String response = post(CHAT_URL, chatRequest(ingredients, allergen).toString());
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JSONObject root = new JSONObject(response);
        if (root.has("error")) throw new IOException("OpenRouter could not complete the request");
        return parseChat(root, ingredients.size());
    }

    JSONObject chatRequest(List<String> ingredients, Allergen allergen) throws JSONException {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", CloudAllergenPrompt.instructions()))
                .put(new JSONObject().put("role", "user")
                        .put("content", CloudAllergenPrompt.data(ingredients, allergen).toString()));
        return new JSONObject().put("model", modelId).put("messages", messages)
                .put("stream", false).put("temperature", 0)
                .put("max_tokens", Math.max(256, ingredients.size() * 48 + 64))
                .put("response_format", new JSONObject().put("type", "json_object"));
    }

    JSONObject decisionsRequest(String ingredient, Allergen allergen) throws JSONException {
        JSONObject question = new JSONObject().put("type", "score")
                .put("instructions", AllergenPrompt.decisionInstructions())
                .put("criteria", new JSONArray()
                        .put("No known or plausible relationship to the target allergen")
                        .put("A known or plausible source of the target allergen, including an ambiguous source"));
        return new JSONObject().put("model", modelId)
                .put("state", AllergenPrompt.data(ingredient, allergen))
                .put("questions", new JSONObject().put(QUESTION_ID, question));
    }

    /**
     * A broken envelope or a provider error is an availability failure (IOException/JSONException).
     * A truncated, refused or malformed answer is this input's problem only, so it raises
     * {@link AiResponseFormatException} and does not count toward stopping the run.
     */
    static List<AiDecision> parseChat(JSONObject response, int ingredientCount)
            throws JSONException, IOException, AiResponseFormatException {
        JSONArray choices = response.getJSONArray("choices");
        if (choices.length() != 1) throw new IOException("OpenRouter did not return one answer");
        JSONObject choice = choices.getJSONObject(0);
        String finish = choice.optString("finish_reason");
        if ("error".equals(finish)) throw new IOException("The model provider failed mid-response");
        if (!"stop".equals(finish)) {
            throw new AiResponseFormatException("The model response was incomplete or refused");
        }
        JSONObject message = choice.getJSONObject("message");
        if (!(message.opt("content") instanceof String) || !message.isNull("refusal")) {
            throw new AiResponseFormatException("The model did not return an answer");
        }
        return CloudAllergenPrompt.parse(message.getString("content"), ingredientCount);
    }

    static AiDecision parseDecisions(JSONObject response, double threshold)
            throws JSONException, AiResponseFormatException {
        JSONObject answers = response.getJSONObject("answers");
        JSONObject answer = answers.optJSONObject(QUESTION_ID);
        if (answers.length() != 1 || answer == null) {
            throw new AiResponseFormatException("Decisions did not return one answer");
        }
        Object score = answer.opt("score");
        if (!"score".equals(answer.optString("type")) || !(score instanceof Number)) {
            throw new AiResponseFormatException("Decisions did not return a score");
        }
        try {
            return AiDecision.scored(((Number) score).doubleValue(), threshold);
        } catch (IllegalArgumentException outOfRange) {
            throw new AiResponseFormatException("Decisions returned an invalid score", outOfRange);
        }
    }

    private String post(String endpoint, String body) throws Exception {
        HttpURLConnection connection = connections.open(new URL(endpoint));
        activeConnection = connection;
        try {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-OpenRouter-Title", "Allergy Check");
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                // Provider bodies can echo requests. Never log or display them, or the API key.
                throw new ApiException(status);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("OpenRouter returned an oversized response");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    @Override
    public void close() {
        closed = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
}
