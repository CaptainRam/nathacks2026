package com.example.allergycheck.crosscheck;

import com.example.allergycheck.analysis.ai.AiResponseFormatException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.allergycheck.crosscheck.FoodTypePromptTest.*;
import static org.junit.Assert.*;

public class OpenRouterFoodTypeModelTest {
    private static final String IMAGE = "data:image/jpeg;base64,YQ==";
    private static final String ANSWER = "{\"food_type\":\"cookies\",\"common_allergen_ids\":[\"MILK\"]}";

    @Test
    public void visionRequestContainsOneImageAndSelectedIdsWithoutLabelOrCredentialData() throws Exception {
        OpenRouterFoodTypeModel model = new OpenRouterFoodTypeModel("private-key", "vision-model");
        JSONObject request = model.request(IMAGE, SELECTED);
        assertEquals("vision-model", request.getString("model"));
        assertFalse(request.getBoolean("stream"));
        assertEquals("json_object", request.getJSONObject("response_format").getString("type"));
        JSONArray messages = request.getJSONArray("messages");
        assertEquals(2, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        JSONArray content = messages.getJSONObject(1).getJSONArray("content");
        assertEquals(2, content.length());
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("image_url", content.getJSONObject(1).getString("type"));
        assertEquals(IMAGE, content.getJSONObject(1).getJSONObject("image_url").getString("url"));
        JSONObject data = new JSONObject(content.getJSONObject(0).getString("text"));
        assertEquals(1, data.length());
        assertEquals(3, data.getJSONArray("selected_allergens").length());
        assertFalse(request.toString().contains("private-key"));
        assertFalse(data.has("ingredients"));
        assertFalse(data.has("findings"));
    }

    @Test
    public void refusesRemoteMissingInvalidAndOversizedImages() {
        OpenRouterFoodTypeModel model = new OpenRouterFoodTypeModel("key", "vision-model");
        for (String image : new String[]{null, "", "https://example.org/photo.jpg",
                "content://photos/1", "data:image/jpeg;base64,", "data:image/jpeg;base64,YQ=",
                "data:image/jpeg;base64,Y=Q=", "data:image/jpeg;base64,YQ==\n",
                "data:text/plain;base64,YQ==", "data:image/jpeg;base64," + "A".repeat(6_000_000)}) {
            assertThrows("Accepted invalid image input", IllegalArgumentException.class,
                    () -> model.request(image, SELECTED));
        }
    }

    @Test
    public void oneBoundedHttpsPostReturnsAdvisoryAndAlwaysDisconnects() throws Exception {
        FakeConnection connection = new FakeConnection(envelope("stop", ANSWER).toString());
        OpenRouterFoodTypeModel model = model(connection);
        FoodTypeEstimate estimate = model.estimate(IMAGE, SELECTED);
        assertEquals("cookies", estimate.foodType());
        assertEquals(Collections.singletonList(MILK), estimate.commonAllergens());
        assertEquals("POST", connection.getRequestMethod());
        assertEquals("Bearer private-key", connection.getRequestProperty("Authorization"));
        assertEquals(15_000, connection.getConnectTimeout());
        assertEquals(60_000, connection.getReadTimeout());
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
        JSONObject sent = new JSONObject(connection.output.toString(StandardCharsets.UTF_8.name()));
        assertEquals("vision-model", sent.getString("model"));
        assertFalse(sent.toString().contains("private-key"));
    }

    @Test
    public void incompleteRefusedOrProviderErrorResponsesCannotBecomeSuccessfulChecks() throws Exception {
        for (String finish : new String[]{"length", "content_filter", "tool_calls", ""}) {
            JSONObject response = envelope(finish, ANSWER);
            assertThrows(AiResponseFormatException.class, () -> OpenRouterFoodTypeModel.parseChat(response, SELECTED));
        }
        JSONObject refused = envelope("stop", ANSWER);
        refused.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal", "Cannot assess");
        assertThrows(AiResponseFormatException.class, () -> OpenRouterFoodTypeModel.parseChat(refused, SELECTED));
        assertThrows(IOException.class, () -> OpenRouterFoodTypeModel.parseChat(envelope("error", ANSWER), SELECTED));
        JSONObject emptyChoices = new JSONObject().put("choices", new JSONArray());
        assertThrows(IOException.class, () -> OpenRouterFoodTypeModel.parseChat(emptyChoices, SELECTED));
        FakeConnection providerError = new FakeConnection("{\"error\":{\"message\":\"private image data\"}}");
        IOException error = assertThrows(IOException.class, () -> model(providerError).estimate(IMAGE, SELECTED));
        assertFalse(error.getMessage().contains("private image data"));
        assertTrue(providerError.disconnected);
    }

    @Test
    public void httpFailuresAndOversizedRepliesAreSanitizedAndDisconnect() throws Exception {
        FakeConnection denied = new FakeConnection("private-key and private-photo");
        denied.status = 401;
        OpenRouterFoodTypeModel.ApiException unauthorized = assertThrows(OpenRouterFoodTypeModel.ApiException.class,
                () -> model(denied).estimate(IMAGE, SELECTED));
        assertEquals(401, unauthorized.status());
        assertFalse(unauthorized.getMessage().contains("private-key"));
        assertTrue(denied.disconnected);
        FakeConnection oversized = new FakeConnection("x".repeat(65_537));
        IOException size = assertThrows(IOException.class, () -> model(oversized).estimate(IMAGE, SELECTED));
        assertTrue(size.getMessage().contains("oversized"));
        assertTrue(oversized.disconnected);
    }

    @Test
    public void closedModelDoesNotOpenAConnectionAndActiveCancellationDisconnects() throws Exception {
        AtomicBoolean opened = new AtomicBoolean();
        OpenRouterFoodTypeModel closed = new OpenRouterFoodTypeModel("key", "vision-model", url -> {
            opened.set(true);
            return new FakeConnection("");
        });
        closed.close();
        assertThrows(InterruptedException.class, () -> closed.estimate(IMAGE, SELECTED));
        assertFalse(opened.get());

        FakeConnection connection = new FakeConnection(envelope("stop", ANSWER).toString());
        OpenRouterFoodTypeModel active = model(connection);
        connection.beforeRead = active::close;
        assertThrows(InterruptedException.class, () -> active.estimate(IMAGE, SELECTED));
        assertTrue(connection.disconnected);
    }

    private static OpenRouterFoodTypeModel model(FakeConnection connection) {
        return new OpenRouterFoodTypeModel("private-key", "vision-model", url -> {
            assertEquals("https://openrouter.ai/api/v1/chat/completions", url.toString());
            return connection;
        });
    }

    private static JSONObject envelope(String finish, String content) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", finish)
                .put("message", new JSONObject().put("content", content))));
    }

    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] response;
        boolean disconnected;
        int status = 200;
        Runnable beforeRead;

        FakeConnection(String response) throws IOException {
            super(new URL("https://openrouter.ai/api/v1/chat/completions"));
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() {
            if (beforeRead != null) beforeRead.run();
            return new ByteArrayInputStream(response);
        }
    }
}
