/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.aiphoneassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LLM client for OpenAI-compatible chat completion APIs with vision support
 * (OpenAI, GLM-4V, Qwen-VL, vLLM, Ollama's /v1 endpoint, one-api gateways...).
 *
 * Configuration is read from the shared preferences written by SettingsActivity
 * on every request, so changes take effect without restarting the service:
 *   endpoint / model / api_key / task
 *
 * The assistant is task-driven: the user's task text is included in the prompt
 * and the model is explicitly allowed to answer "none" when no action helps,
 * instead of inventing taps. Returned coordinates are in the coordinate space
 * of the (downscaled) image the model saw; this class rescales them back to
 * real screen pixels before handing the action to the caller.
 */
public class LLMClientService {
    private static final String TAG = "AIPhoneAssistant.LLM";

    public static final String PREFS_NAME = "ai_phone_assistant";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_TASK = "task";
    public static final String KEY_CAPTURE_INTERVAL = "capture_interval";
    public static final int DEFAULT_CAPTURE_INTERVAL_MS = 500;

    // Image compression settings. The model receives a downscaled image; the
    // scale factor between it and the real screen is applied to returned
    // coordinates so injections land where the model intended.
    private static final int JPEG_QUALITY = 80;
    private static final int MAX_IMAGE_SIZE = 1024;

    // HTTP timeouts
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final Context mContext;
    private final ExecutorService mExecutor;
    private final Handler mMainHandler;

    public LLMClientService(Context context) {
        mContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /** Read a config value from the shared preferences. */
    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Analyze a full-resolution screenshot against the configured task.
     *
     * @param screenshot full-resolution screen bitmap
     * @param screenW real screen width in pixels
     * @param screenH real screen height in pixels
     * @param callback invoked on the main thread with an action whose
     *                 coordinates are already in real screen pixels (or null
     *                 when the model chose no action)
     */
    public void analyzeScreen(Bitmap screenshot, int screenW, int screenH,
            LLMCallback callback) {
        SharedPreferences prefs = getPrefs(mContext);
        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();
        String apiKey = prefs.getString(KEY_API_KEY, "").trim();
        String model = prefs.getString(KEY_MODEL, "gpt-4o").trim();
        String task = prefs.getString(KEY_TASK, "").trim();

        if (endpoint.isEmpty() || task.isEmpty()) {
            if (callback != null) {
                callback.onError("LLM endpoint or task not configured");
            }
            return;
        }

        mExecutor.execute(() -> {
            try {
                Bitmap scaled = scaleBitmap(screenshot);
                String imageBase64 = bitmapToBase64(scaled);
                String prompt = buildPrompt(task, scaled.getWidth(), scaled.getHeight());
                String response = sendToLLM(endpoint, apiKey, model, prompt, imageBase64);
                InputInjectionService.InputAction action =
                        parseResponse(response, screenW, screenH,
                                scaled.getWidth(), scaled.getHeight());
                if (scaled != screenshot) {
                    scaled.recycle();
                }
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onLLMResponse(response, action);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "LLM analysis failed", e);
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Send a text-only ping to the configured endpoint; used by Settings'
     * "Test Connection" button. Callback is invoked on the main thread.
     */
    public void testConnection(TestCallback callback) {
        SharedPreferences prefs = getPrefs(mContext);
        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();
        String apiKey = prefs.getString(KEY_API_KEY, "").trim();
        String model = prefs.getString(KEY_MODEL, "gpt-4o").trim();

        if (endpoint.isEmpty()) {
            if (callback != null) {
                callback.onResult(false, "Endpoint not configured");
            }
            return;
        }

        mExecutor.execute(() -> {
            try {
                String reply = sendToLLM(endpoint, apiKey, model,
                        "Reply with the single word OK and nothing else.", null);
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onResult(true, "Model replied: " + shorten(reply, 60));
                    }
                });
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onResult(false, shorten(message, 200));
                    }
                });
            }
        });
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String buildPrompt(String task, int imageWidth, int imageHeight) {
        return "You are shown a screenshot of an Android phone (image size: "
                + imageWidth + "x" + imageHeight + " pixels). "
                + "The user's task: \"" + task + "\".\n"
                + "Decide the single best next action to progress the task, then answer with "
                + "ONLY one JSON object and no other text:\n"
                + "{\"action\":\"tap\",\"x\":<int>,\"y\":<int>}\n"
                + "{\"action\":\"swipe\",\"x\":<int>,\"y\":<int>,\"endX\":<int>,\"endY\":<int>,"
                + "\"durationMs\":<int>}\n"
                + "{\"action\":\"long_press\",\"x\":<int>,\"y\":<int>,\"durationMs\":<int>}\n"
                + "{\"action\":\"scroll\",\"x\":<int>,\"y\":<int>,\"deltaY\":<int>}"
                + "  // negative deltaY scrolls content up\n"
                + "{\"action\":\"key_press\",\"key\":\"BACK\"}"
                + "  // BACK, HOME, APP_SWITCH, ENTER, DEL, DPAD_UP, DPAD_DOWN, VOLUME_UP...\n"
                + "{\"action\":\"text_input\",\"text\":\"...\"}"
                + "  // only when a text field is focused\n"
                + "{\"action\":\"wait\",\"durationMs\":<int>}"
                + "  // when the screen is still loading\n"
                + "{\"action\":\"none\"}"
                + "  // no action helps, or the task is already complete\n"
                + "Coordinates must be in image pixel space. Rules: tap precisely on visible "
                + "controls; if the previous action visibly failed, try a different approach; "
                + "when unsure or when the task is finished, choose \"none\".";
    }

    /** Downscale so the largest dimension is at most {@link #MAX_IMAGE_SIZE}. */
    private Bitmap scaleBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxDim = Math.max(width, height);
        if (maxDim <= MAX_IMAGE_SIZE) {
            return bitmap;
        }
        float scale = (float) MAX_IMAGE_SIZE / maxDim;
        return Bitmap.createScaledBitmap(bitmap,
                Math.round(width * scale), Math.round(height * scale), true);
    }

    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * POST a chat completion request. When imageBase64 is null the user message
     * is sent as plain text (used by the connection test).
     */
    private String sendToLLM(String endpoint, String apiKey, String model, String prompt,
            String imageBase64) throws IOException {
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("model", model);

            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            if (imageBase64 != null) {
                JSONArray content = new JSONArray();
                JSONObject textContent = new JSONObject();
                textContent.put("type", "text");
                textContent.put("text", prompt);
                content.put(textContent);
                JSONObject imageContent = new JSONObject();
                imageContent.put("type", "image_url");
                JSONObject imageUrl = new JSONObject();
                imageUrl.put("url", "data:image/jpeg;base64," + imageBase64);
                imageContent.put("image_url", imageUrl);
                content.put(imageContent);
                userMessage.put("content", content);
            } else {
                userMessage.put("content", prompt);
            }
            messages.put(userMessage);
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 500);
        } catch (JSONException e) {
            throw new IOException("Failed to build request", e);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }

            byte[] payload = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readStream(connection.getErrorStream());
                throw new IOException("HTTP " + responseCode + " "
                        + shorten(error.isEmpty() ? connection.getResponseMessage() : error, 300));
            }

            String responseBody = readStream(connection.getInputStream());
            try {
                JSONObject jsonResponse = new JSONObject(responseBody);
                JSONArray choices = jsonResponse.getJSONArray("choices");
                if (choices.length() == 0) {
                    throw new IOException("Empty choices in LLM response");
                }
                return choices.getJSONObject(0).getJSONObject("message").getString("content");
            } catch (JSONException e) {
                throw new IOException("Failed to parse LLM response: "
                        + shorten(responseBody, 200), e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    /**
     * Parse the model's reply into an action with REAL screen coordinates.
     * Returns null for "none"/"done" style answers, malformed replies or
     * actions with missing required fields — never a guessed default tap.
     */
    private InputInjectionService.InputAction parseResponse(String response, int screenW,
            int screenH, int imageW, int imageH) {
        if (response == null) {
            return null;
        }
        try {
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No JSON found in response: " + shorten(response, 200));
                return null;
            }
            JSONObject json = new JSONObject(response.substring(jsonStart, jsonEnd + 1));
            String actionType = json.optString("action", "").trim().toLowerCase();

            InputInjectionService.InputAction action;
            switch (actionType) {
                case "tap":
                case "click":
                    if (!json.has("x") || !json.has("y")) {
                        Log.w(TAG, "tap without coordinates, ignoring");
                        return null;
                    }
                    action = InputInjectionService.InputAction.tap(
                            (float) json.getDouble("x"), (float) json.getDouble("y"));
                    break;

                case "swipe":
                    if (!json.has("x") || !json.has("y")
                            || !json.has("endX") || !json.has("endY")) {
                        Log.w(TAG, "swipe missing endpoints, ignoring");
                        return null;
                    }
                    action = InputInjectionService.InputAction.swipe(
                            (float) json.getDouble("x"), (float) json.getDouble("y"),
                            (float) json.getDouble("endX"), (float) json.getDouble("endY"),
                            clampMs(json.optLong("durationMs", 300)));
                    break;

                case "long_press":
                    if (!json.has("x") || !json.has("y")) {
                        return null;
                    }
                    action = InputInjectionService.InputAction.longPress(
                            (float) json.getDouble("x"), (float) json.getDouble("y"),
                            clampMs(json.optLong("durationMs", 800)));
                    break;

                case "scroll":
                case "scroll_up":
                case "scroll_down":
                    if (!json.has("x") || !json.has("y")) {
                        return null;
                    }
                    float deltaY = (float) json.optDouble("deltaY",
                            "scroll_down".equals(actionType) ? 500 : -500);
                    action = InputInjectionService.InputAction.scroll(
                            (float) json.getDouble("x"), (float) json.getDouble("y"), deltaY);
                    break;

                case "key_press": {
                    int keyCode = 0;
                    if (json.has("key")) {
                        keyCode = InputInjectionService.keyNameToKeyCode(json.getString("key"));
                    } else if (json.has("keyCode")) {
                        keyCode = json.optInt("keyCode", 0);
                        if (keyCode == 0) {
                            keyCode = InputInjectionService.keyNameToKeyCode(
                                    json.optString("keyCode"));
                        }
                    }
                    if (keyCode == 0) {
                        Log.w(TAG, "key_press with unknown key: " + json);
                        return null;
                    }
                    action = InputInjectionService.InputAction.keyPress(keyCode);
                    break;
                }

                case "text_input":
                case "type": {
                    String text = json.optString("text", "");
                    if (text.isEmpty()) {
                        return null;
                    }
                    action = InputInjectionService.InputAction.textInput(text);
                    break;
                }

                case "wait":
                    action = InputInjectionService.InputAction.waitAction(
                            json.optLong("durationMs", 1000));
                    break;

                case "none":
                case "done":
                case "finish":
                case "complete":
                case "stop":
                    Log.i(TAG, "Model chose no action (" + actionType + ")");
                    return null;

                default:
                    Log.w(TAG, "Unknown action type: " + actionType);
                    return null;
            }

            // The model saw the downscaled image; map coordinates back to the
            // real screen so injections land where the model intended.
            float sx = screenW / (float) imageW;
            float sy = screenH / (float) imageH;
            action.x *= sx;
            action.y *= sy;
            action.endX *= sx;
            action.endY *= sy;
            action.deltaY *= sy;
            return action;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing response", e);
            return null;
        }
    }

    private static long clampMs(long value) {
        return Math.min(Math.max(value, 50), 10000);
    }

    public void release() {
        mExecutor.shutdown();
    }

    /** Callback for screen analysis. */
    public interface LLMCallback {
        void onLLMResponse(String response, InputInjectionService.InputAction action);
        void onError(String error);
    }

    /** Callback for the connection test. */
    public interface TestCallback {
        void onResult(boolean success, String detail);
    }
}
