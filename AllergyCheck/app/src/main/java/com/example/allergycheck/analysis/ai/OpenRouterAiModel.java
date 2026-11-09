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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
        private final long retryAtMillis;
        private final boolean modelConfigurationError;

        public ApiException(int status) {
            this(status, 0);
        }

        public ApiException(int status, long retryAtMillis) {
            this(status, retryAtMillis, status == 404);
        }

        private ApiException(int status, long retryAtMillis, boolean modelConfigurationError) {
            super(status == 401 ? "OpenRouter rejected the API key"
                    : status == 402 ? "OpenRouter credits or quota are exhausted"
                    : status == 429 ? "OpenRouter rate limit reached; try again later"
                    : "OpenRouter request failed (HTTP " + status + ")");
            this.status = status;
            this.retryAtMillis = Math.max(0, retryAtMillis);
            this.modelConfigurationError = modelConfigurationError;
        }

        public int status() { return status; }
        public long retryAtMillis() { return retryAtMillis; }
        public boolean modelConfigurationError() { return modelConfigurationError; }

        /** Stop queued work instead of sending more requests into the service's cooldown. */
        public boolean stopsFurtherRequests() {
            return permanent() || status == 429 || retryAtMillis > System.currentTimeMillis();
        }

        /**
         * Only account-wide problems: a bad key, no credits, or an unknown model. OpenRouter also
         * sends 400 (e.g. context too long) and 403 (moderation) for a single input, which must not
         * stop the other allergens; a truly broken setup still hits the consecutive-failure limit.
         */
        public boolean permanent() {
            // 402 with Retry-After is an in-flight budget limit, rather than exhausted credits.
            return status == 401 || (status == 402 && retryAtMillis == 0)
                    || modelConfigurationError;
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
            long retryAt = retryAtMillis(connection.getHeaderField("Retry-After"),
                    System.currentTimeMillis());
            if (status < 200 || status >= 300) {
                // Provider bodies can echo requests. Never log or display them, or the API key.
                throw new ApiException(status, retryAt,
                        status == 404 || (status == 400 && isInvalidConfiguredModel(connection)));
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
                String response = output.toString(StandardCharsets.UTF_8.name());
                JSONObject envelope = new JSONObject(response);
                if (envelope.has("error")) {
                    JSONObject error = envelope.optJSONObject("error");
                    Object code = error == null ? null : error.opt("code");
                    int errorStatus = code instanceof Number
                            && ((Number) code).doubleValue() == ((Number) code).intValue()
                            ? ((Number) code).intValue() : 502;
                    if (errorStatus < 400 || errorStatus > 599) errorStatus = 502;
                    // HTTP 200 can carry a provider failure after generation has begun.
                    // Retain only its typed code, never its message or metadata.
                    throw new ApiException(errorStatus, retryAt, errorStatus == 404
                            || (errorStatus == 400 && isInvalidConfiguredModel(error)));
                }
                return response;
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    private boolean isInvalidConfiguredModel(HttpURLConnection connection) {
        try (InputStream input = connection.getErrorStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return false;
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()
                        || output.size() + count > MAX_RESPONSE_BYTES) return false;
                output.write(buffer, 0, count);
            }
            return isInvalidConfiguredModel(new JSONObject(
                    output.toString(StandardCharsets.UTF_8.name())).optJSONObject("error"));
        } catch (IOException | JSONException | RuntimeException invalid) {
            return false;
        }
    }

    private boolean isInvalidConfiguredModel(JSONObject error) {
        return error != null && error.optInt("code") == 400
                && (modelId + " is not a valid model ID").equals(error.optString("message"));
    }

    private static long retryAtMillis(String header, long now) {
        if (header == null) return 0;
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) return 0;
            return seconds > (Long.MAX_VALUE - now) / 1000 ? Long.MAX_VALUE : now + seconds * 1000;
        } catch (NumberFormatException ignored) {
            try {
                long date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
                return date > now ? date : 0;
            } catch (DateTimeParseException | ArithmeticException invalid) {
                return 0;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
}
