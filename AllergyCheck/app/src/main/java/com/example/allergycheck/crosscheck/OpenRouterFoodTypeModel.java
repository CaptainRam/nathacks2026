package com.example.allergycheck.crosscheck;

import com.example.allergycheck.analysis.Allergen;
import com.example.allergycheck.analysis.ai.AiResponseFormatException;

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
import java.util.ArrayList;
import java.util.List;

/** A cancellable worker-thread vision request, independent of ingredient-analysis mode. */
public final class OpenRouterFoodTypeModel implements AutoCloseable {
    private static final String ENDPOINT = "https://openrouter.ai/api/v1/chat/completions";
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final int MAX_IMAGE_DATA_CHARS = 6_000_000;
    private final String apiKey;
    private final String modelId;
    private final ConnectionFactory connections;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean closed;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    /** Sanitized account/provider failure; response bodies can contain private image data. */
    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        ApiException(int status) {
            super("Food-photo cross-check failed (HTTP " + status + ")");
            this.status = status;
        }

        public int status() { return status; }
    }

    public OpenRouterFoodTypeModel(String apiKey, String modelId) {
        this(apiKey, modelId, url -> (HttpURLConnection) url.openConnection());
    }

    OpenRouterFoodTypeModel(String apiKey, String modelId, ConnectionFactory connections) {
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.contains("\r") || apiKey.contains("\n")) {
            throw new IllegalArgumentException("Add an OpenRouter API key in Settings");
        }
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("Choose a vision model for the food-photo cross-check in Settings");
        }
        if (connections == null) throw new IllegalArgumentException("A connection factory is required");
        this.apiKey = apiKey.trim();
        this.modelId = modelId.trim();
        this.connections = connections;
    }

    /** Send only an explicitly supplied front image and selected-allergen names/IDs. */
    public FoodTypeEstimate estimate(String imageDataUrl, List<Allergen> selectedAllergens) throws Exception {
        checkCancelled();
        List<Allergen> selected = selectedAllergens == null ? null : new ArrayList<>(selectedAllergens);
        String response = post(request(imageDataUrl, selected).toString());
        checkCancelled();
        JSONObject root = new JSONObject(response);
        if (root.has("error")) throw new IOException("The photo model could not complete the request");
        return parseChat(root, selected);
    }

    JSONObject request(String imageDataUrl, List<Allergen> selectedAllergens) throws JSONException {
        validateImage(imageDataUrl);
        JSONArray content = new JSONArray()
                .put(new JSONObject().put("type", "text")
                        .put("text", FoodTypePrompt.data(selectedAllergens).toString()))
                .put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", imageDataUrl)));
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", FoodTypePrompt.instructions()))
                .put(new JSONObject().put("role", "user").put("content", content));
        return new JSONObject().put("model", modelId).put("messages", messages)
                .put("stream", false).put("temperature", 0).put("max_tokens", 512)
                .put("response_format", new JSONObject().put("type", "json_object"));
    }

    static FoodTypeEstimate parseChat(JSONObject response, List<Allergen> selectedAllergens)
            throws JSONException, IOException, AiResponseFormatException, UnknownFoodTypeException {
        JSONArray choices = response.getJSONArray("choices");
        if (choices.length() != 1) throw new IOException("The photo model did not return one answer");
        JSONObject choice = choices.getJSONObject(0);
        if ("error".equals(choice.optString("finish_reason"))) {
            throw new IOException("The photo model provider failed mid-response");
        }
        if (!"stop".equals(choice.optString("finish_reason"))) {
            throw new AiResponseFormatException("The photo model response was incomplete or refused");
        }
        JSONObject message = choice.getJSONObject("message");
        if (!(message.opt("content") instanceof String) || !message.isNull("refusal")) {
            throw new AiResponseFormatException("The photo model did not return an answer");
        }
        return FoodTypePrompt.parse(message.getString("content"), selectedAllergens);
    }

    private static void validateImage(String dataUrl) {
        if (dataUrl == null || dataUrl.length() > MAX_IMAGE_DATA_CHARS) {
            throw new IllegalArgumentException("The front photo is missing or too large");
        }
        int start;
        if (dataUrl.startsWith("data:image/jpeg;base64,")) start = "data:image/jpeg;base64,".length();
        else if (dataUrl.startsWith("data:image/png;base64,")) start = "data:image/png;base64,".length();
        else if (dataUrl.startsWith("data:image/webp;base64,")) start = "data:image/webp;base64,".length();
        else throw new IllegalArgumentException("The front photo must be an encoded JPEG, PNG or WebP image");
        if (dataUrl.length() == start || (dataUrl.length() - start) % 4 != 0) {
            throw new IllegalArgumentException("The front photo has invalid base64 data");
        }
        int padding = 0;
        for (int i = start; i < dataUrl.length(); i++) {
            char c = dataUrl.charAt(i);
            if (c == '=') {
                if (++padding > 2 || i < dataUrl.length() - 2) {
                    throw new IllegalArgumentException("The front photo has invalid base64 padding");
                }
            } else if (padding > 0 || !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '+' || c == '/')) {
                throw new IllegalArgumentException("The front photo has invalid base64 data");
            }
        }
    }

    private String post(String body) throws Exception {
        HttpURLConnection connection = connections.open(new URL(ENDPOINT));
        activeConnection = connection;
        try {
            checkCancelled();
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
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            checkCancelled();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException(status);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("The photo model returned an oversized response");
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

    private void checkCancelled() throws InterruptedException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    @Override
    public void close() {
        closed = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
}
