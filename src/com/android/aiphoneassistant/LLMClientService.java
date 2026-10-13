/*
 * Copyright (C) 2026 The AgentVisOS Project
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM client for OpenAI-compatible chat completion APIs with vision support
 * (OpenAI, GLM-4V, Qwen-VL, vLLM, Ollama's /v1 endpoint, one-api gateways...).
 *
 * Configuration is read from the shared preferences written by SettingsActivity
 * on every request, so changes take effect without restarting the service:
 *   backend / endpoint / model / api_key / local_model_path / local_accel
 *
 * With backend "local" (only in builds that include LiteRT-LM, see
 * {@link LocalInference}) the same prompt and screenshot are run on-device,
 * e.g. by Gemma 4 E2B, instead of over HTTP; prompt building and reply
 * parsing are shared by both transports.
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
    public static final String KEY_BACKEND = "backend";
    public static final String KEY_LOCAL_MODEL_PATH = "local_model_path";
    public static final String KEY_LOCAL_ACCEL = "local_accel";
    public static final String BACKEND_REMOTE = "remote";
    public static final String BACKEND_LOCAL = "local";
    public static final int DEFAULT_CAPTURE_INTERVAL_MS = 500;

    // Image compression settings. The model receives a downscaled image; the
    // scale factor between it and the real screen is applied to returned
    // coordinates so injections land where the model intended.
    private static final int JPEG_QUALITY = 80;
    private static final int MAX_IMAGE_SIZE = 1024;
    // On-device the vision encoder resizes anyway; a smaller JPEG keeps the
    // decode + preprocessing cheap.
    private static final int LOCAL_MAX_IMAGE_SIZE = 768;
    private static final int NORMALIZED_COORDS = 1000;

    // JSON schema for constrained decoding on-device: one action object with
    // the fields parseDecision understands, coordinates in 0-1000.
    private static final String COORD =
            "{\"type\":\"integer\",\"minimum\":0,\"maximum\":1000}";
    private static final String ACTION_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"action\":{\"type\":\"string\",\"enum\":[\"tap\",\"swipe\","
            + "\"long_press\",\"scroll\",\"key_press\",\"text_input\",\"wait\","
            + "\"none\",\"done\"]},"
            + "\"x\":" + COORD + ",\"y\":" + COORD + ","
            + "\"endX\":" + COORD + ",\"endY\":" + COORD + ","
            + "\"deltaY\":{\"type\":\"integer\"},\"durationMs\":{\"type\":\"integer\"},"
            + "\"key\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},"
            + "\"summary\":{\"type\":\"string\"}},"
            + "\"required\":[\"action\"],\"additionalProperties\":false}";

    // HTTP timeouts
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final Context mContext;
    private final ExecutorService mExecutor;
    private final Handler mMainHandler;

    // The connection of the request currently in flight, kept so the task can
    // be interrupted (disconnect() aborts the blocking read on the executor).
    private final AtomicReference<HttpURLConnection> mInFlight = new AtomicReference<>();
    private final AtomicBoolean mCancelled = new AtomicBoolean(false);
    /** Null when this build has no on-device backend. */
    private final LocalInference mLocal;

    public LLMClientService(Context context) {
        mContext = context.getApplicationContext();
        mExecutor = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());
        mLocal = LocalInference.get(mContext);
    }

    /** Read a config value from the shared preferences. */
    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Local backend selected AND compiled into this build. */
    public static boolean isLocalBackend(SharedPreferences prefs) {
        return LocalInference.isAvailable()
                && BACKEND_LOCAL.equals(prefs.getString(KEY_BACKEND, BACKEND_REMOTE));
    }

    /** Configured on-device model path, defaulting to the app's files dir. */
    public static String localModelPath(Context context) {
        String path = getPrefs(context).getString(KEY_LOCAL_MODEL_PATH, "").trim();
        return path.isEmpty() ? LocalInference.defaultModelFile(context).getPath() : path;
    }

    public static String localAccel(Context context) {
        return getPrefs(context).getString(KEY_LOCAL_ACCEL, LocalInference.ACCEL_GPU);
    }

    /** Whether the selected backend has what it needs to serve a request. */
    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (isLocalBackend(prefs)) {
            return new java.io.File(localModelPath(context)).canRead();
        }
        return !prefs.getString(KEY_ENDPOINT, "").trim().isEmpty();
    }

    /**
     * Analyze a full-resolution screenshot against the given task.
     *
     * @param screenshot full-resolution screen bitmap
     * @param screenW real screen width in pixels
     * @param screenH real screen height in pixels
     * @param task the active task text (the assistant now has one task at a
     *             time, managed via ChatStore — no longer read from prefs)
     * @param recentActions descriptions of actions already performed for this
     *                      task, oldest first; included in the prompt so the
     *                      model avoids repeating itself and can tell when
     *                      the goal is reached
     * @param callback invoked on the main thread with a decision whose action
     *                 coordinates are already in real screen pixels
     */
    public void analyzeScreen(Bitmap screenshot, int screenW, int screenH, String task,
            List<String> recentActions, LLMCallback callback) {
        SharedPreferences prefs = getPrefs(mContext);
        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();
        String apiKey = prefs.getString(KEY_API_KEY, "").trim();
        String model = prefs.getString(KEY_MODEL, "gpt-4o").trim();
        boolean local = isLocalBackend(prefs);
        String modelPath = localModelPath(mContext);
        String accel = localAccel(mContext);

        if ((!local && endpoint.isEmpty()) || task == null || task.trim().isEmpty()) {
            if (callback != null) {
                callback.onError("LLM endpoint or task not configured");
            }
            return;
        }

        mCancelled.set(false);
        // A dedicated thread per request: a hung gateway response must never
        // block later analysis rounds (the single-thread executor would).
        Thread t = new Thread(() -> {
            try {
                Bitmap scaled = scaleBitmap(screenshot,
                        local ? LOCAL_MAX_IMAGE_SIZE : MAX_IMAGE_SIZE);
                // Gemma grounds in Gemini-style coordinates normalized to
                // 0-1000 regardless of the image size (asking for pixels
                // still yields 0-1000 values), so the local prompt asks for
                // that space and replies are rescaled from 1000x1000.
                int coordW = local ? NORMALIZED_COORDS : scaled.getWidth();
                int coordH = local ? NORMALIZED_COORDS : scaled.getHeight();
                String prompt = buildPrompt(task, scaled.getWidth(), scaled.getHeight(),
                        recentActions, local);
                String response;
                if (local) {
                    requireLocal();
                    response = mLocal.generate(modelPath, accel, prompt, bitmapToJpeg(scaled),
                            ACTION_SCHEMA);
                    Log.i(TAG, "Local reply: " + shorten(response, 200));
                } else {
                    String imageBase64 = Base64.encodeToString(bitmapToJpeg(scaled),
                            Base64.NO_WRAP);
                    response = sendToLLM(endpoint, apiKey, model, prompt, imageBase64);
                }
                LLMDecision parsed = parseDecision(response, screenW, screenH,
                        coordW, coordH);
                // The prompt history must be in the model's coordinate space,
                // or it copies screen-pixel values back as 0-1000 coordinates.
                // The constrained reply is exactly the action JSON.
                LLMDecision decision = local
                        ? parsed.withHistoryEntry(shorten(response, 200)) : parsed;
                if (scaled != screenshot) {
                    scaled.recycle();
                }
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onDecision(decision);
                    }
                });
            } catch (Exception e) {
                if (mCancelled.get()) {
                    Log.i(TAG, "LLM request cancelled");
                    mMainHandler.post(() -> {
                        if (callback != null) {
                            callback.onInterrupted();
                        }
                    });
                    return;
                }
                Log.e(TAG, "LLM analysis failed", e);
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError(e.getMessage());
                    }
                });
            }
        });
        t.setName("llm-analyze");
        t.start();
    }

    /**
     * Abort the request currently in flight (used by the user interrupt). The
     * blocked read fails on the executor thread and surfaces as
     * {@link LLMCallback#onInterrupted()}.
     */
    public void cancelInFlight() {
        mCancelled.set(true);
        if (mLocal != null) {
            mLocal.cancel();
        }
        HttpURLConnection connection = mInFlight.get();
        if (connection != null) {
            connection.disconnect();
        }
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

        if (isLocalBackend(prefs)) {
            testLocal(callback);
            return;
        }
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

    /** Load the on-device model (if needed) and run a tiny text prompt. */
    private void testLocal(TestCallback callback) {
        String modelPath = localModelPath(mContext);
        String accel = localAccel(mContext);
        mExecutor.execute(() -> {
            try {
                long t0 = android.os.SystemClock.elapsedRealtime();
                requireLocal();
                String reply = mLocal.generate(modelPath, accel,
                        "Reply with the single word OK and nothing else.", null, null);
                long ms = android.os.SystemClock.elapsedRealtime() - t0;
                String detail = "Model replied: " + shorten(reply, 40) + " (" + ms + " ms, "
                        + mLocal.loadedAccel() + ")";
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onResult(true, detail);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Local model test failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                mMainHandler.post(() -> {
                    if (callback != null) {
                        callback.onResult(false, shorten(message, 200));
                    }
                });
            }
        });
    }

    private void requireLocal() throws IOException {
        if (mLocal == null) {
            throw new IOException("On-device backend failed to initialize");
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String buildPrompt(String task, int imageWidth, int imageHeight,
            List<String> recentActions, boolean normalizedCoords) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are shown a screenshot of an Android phone");
        if (!normalizedCoords) {
            prompt.append(" (image size: ").append(imageWidth).append("x")
                    .append(imageHeight).append(" pixels)");
        }
        prompt.append(". ")
                .append("The user's task: \"").append(task).append("\".\n");
        if (recentActions != null && !recentActions.isEmpty()) {
            prompt.append("Actions you already performed for this task, oldest first:\n");
            for (int i = 0; i < recentActions.size(); i++) {
                prompt.append(i + 1).append(". ").append(recentActions.get(i)).append('\n');
            }
            prompt.append("Do not repeat an action that did not help; try another approach.\n");
        }
        prompt.append("Decide the single best next action to progress the task, then answer with ")
                .append("ONLY one JSON object and no other text:\n")
                .append("{\"action\":\"tap\",\"x\":<int>,\"y\":<int>}\n")
                .append("{\"action\":\"swipe\",\"x\":<int>,\"y\":<int>,\"endX\":<int>,\"endY\":<int>,")
                .append("\"durationMs\":<int>}\n")
                .append("{\"action\":\"long_press\",\"x\":<int>,\"y\":<int>,\"durationMs\":<int>}\n")
                .append("{\"action\":\"scroll\",\"x\":<int>,\"y\":<int>,\"deltaY\":<int>}")
                .append("  // negative deltaY scrolls content up\n")
                .append("{\"action\":\"key_press\",\"key\":\"BACK\"}")
                .append("  // BACK, HOME, APP_SWITCH, ENTER, DEL, DPAD_UP, DPAD_DOWN, VOLUME_UP...\n")
                .append("{\"action\":\"text_input\",\"text\":\"...\"}")
                .append("  // only when a text field is focused\n")
                .append("{\"action\":\"wait\",\"durationMs\":<int>}")
                .append("  // when the screen is still loading\n")
                .append("{\"action\":\"none\"}")
                .append("  // nothing useful can be done right now; prefer \"wait\" for loading\n")
                .append("{\"action\":\"done\",\"summary\":\"<one short sentence>\"}")
                .append("  // ONLY when the task's goal is now VISIBLY achieved on this screen; ")
                .append("write the summary in the same language as the user's task\n")
                .append(normalizedCoords
                        ? "Coordinates are normalized to 0-" + NORMALIZED_COORDS + " on both axes "
                                + "(0,0 = top-left, " + NORMALIZED_COORDS + "," + NORMALIZED_COORDS
                                + " = bottom-right of the screenshot). "
                        : "Coordinates must be in image pixel space. ")
                .append("Rules: tap precisely on visible ")
                .append("controls; if the previous action visibly failed, try a different approach; ")
                .append("choose \"done\" when the goal is clearly reached, \"wait\" while loading, ")
                .append("\"none\" only when stuck with nothing to try.");
        return prompt.toString();
    }

    /** Downscale so the largest dimension is at most {@code maxSize}. */
    private Bitmap scaleBitmap(Bitmap bitmap, int maxSize) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int maxDim = Math.max(width, height);
        if (maxDim <= maxSize) {
            return bitmap;
        }
        float scale = (float) maxSize / maxDim;
        return Bitmap.createScaledBitmap(bitmap,
                Math.round(width * scale), Math.round(height * scale), true);
    }

    private byte[] bitmapToJpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        return out.toByteArray();
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
            // Generous budget: reasoning models spend tokens on the hidden
            // chain-of-thought before the JSON answer.
            requestBody.put("max_tokens", 4096);
            // Stream (SSE): reasoning models can think for minutes at high
            // max_tokens; a non-streaming POST would sit with zero bytes and
            // get cut by intermediate proxies. Deltas accumulate in
            // readStreamSSE below.
            requestBody.put("stream", true);
        } catch (JSONException e) {
            throw new IOException("Failed to build request", e);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        mInFlight.set(connection);
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(
                    new URL(endpoint).getHost());
            Log.i(TAG, "DNS: " + new URL(endpoint).getHost() + " -> "
                    + java.util.Arrays.toString(addrs));
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
            Log.i(TAG, "HTTP response " + responseCode + " after "
                    + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
            if (responseCode != HttpURLConnection.HTTP_OK) {
                String error = readStream(connection.getErrorStream());
                throw new IOException("HTTP " + responseCode + " "
                        + shorten(error.isEmpty() ? connection.getResponseMessage() : error, 300));
            }

            String content = readStreamSSE(connection);
            if (content.trim().isEmpty()) {
                throw new IOException("Empty streamed response from model");
            }
            return content;
        } finally {
            mInFlight.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    /**
     * Consume an OpenAI-compatible SSE stream, returning the concatenated
     * answer (content, falling back to the reasoning field for thinking
     * models that never emit a final content delta).
     */
    private String readStreamSSE(HttpURLConnection connection) throws IOException {
        long t0 = android.os.SystemClock.elapsedRealtime();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        boolean gotAnything = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (!gotAnything) {
                    gotAnything = true;
                    Log.i(TAG, "First stream line after "
                            + (android.os.SystemClock.elapsedRealtime() - t0)
                            + " ms: " + line);
                }
                if (data.equals("[DONE]")) {
                    break;
                }
                try {
                    JSONObject chunk = new JSONObject(data);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    content.append(delta.optString("content", ""));
                    reasoning.append(delta.optString("reasoning_content", ""));
                } catch (JSONException ignored) {
                    // keep-alive comments / partial lines
                }
            }
        }
        String answer = content.toString().trim();
        if (answer.isEmpty()) {
            answer = reasoning.toString().trim();
            if (!answer.isEmpty()) {
                Log.i(TAG, "Empty content; falling back to reasoning_content");
            }
        }
        return answer;
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
     * Parse the model's reply into a {@link LLMDecision} with REAL screen
     * coordinates. Malformed replies or actions with missing required fields
     * yield {@code none} — never a guessed default tap.
     */
    private LLMDecision parseDecision(String response, int screenW,
            int screenH, int imageW, int imageH) {
        if (response == null) {
            return LLMDecision.none(null);
        }
        try {
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No JSON found in response: " + shorten(response, 200));
                return LLMDecision.none(response);
            }
            JSONObject json = new JSONObject(response.substring(jsonStart, jsonEnd + 1));
            String actionType = json.optString("action", "").trim().toLowerCase();

            // Task completion. The legacy synonyms map to done as well — models
            // trained on older prompts still answer finish/complete/stop.
            switch (actionType) {
                case "done":
                case "finish":
                case "complete":
                case "stop": {
                    String summary = json.optString("summary", "").trim();
                    if (summary.isEmpty()) {
                        summary = json.optString("text", "").trim();
                    }
                    Log.i(TAG, "Model reports task done: " + shorten(summary, 80));
                    return LLMDecision.done(summary, response);
                }
                case "none":
                    Log.i(TAG, "Model chose no action");
                    return LLMDecision.none(response);
                default:
                    break;
            }

            InputInjectionService.InputAction action;
            switch (actionType) {
                case "tap":
                case "click":
                    if (!json.has("x") || !json.has("y")) {
                        Log.w(TAG, "tap without coordinates, ignoring");
                        return LLMDecision.none(response);
                    }
                    action = InputInjectionService.InputAction.tap(
                            (float) json.getDouble("x"), (float) json.getDouble("y"));
                    break;

                case "swipe":
                    if (!json.has("x") || !json.has("y")
                            || !json.has("endX") || !json.has("endY")) {
                        Log.w(TAG, "swipe missing endpoints, ignoring");
                        return LLMDecision.none(response);
                    }
                    action = InputInjectionService.InputAction.swipe(
                            (float) json.getDouble("x"), (float) json.getDouble("y"),
                            (float) json.getDouble("endX"), (float) json.getDouble("endY"),
                            clampMs(json.optLong("durationMs", 300)));
                    break;

                case "long_press":
                    if (!json.has("x") || !json.has("y")) {
                        return LLMDecision.none(response);
                    }
                    action = InputInjectionService.InputAction.longPress(
                            (float) json.getDouble("x"), (float) json.getDouble("y"),
                            clampMs(json.optLong("durationMs", 800)));
                    break;

                case "scroll":
                case "scroll_up":
                case "scroll_down":
                    if (!json.has("x") || !json.has("y")) {
                        return LLMDecision.none(response);
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
                        return LLMDecision.none(response);
                    }
                    action = InputInjectionService.InputAction.keyPress(keyCode);
                    break;
                }

                case "text_input":
                case "type": {
                    String text = json.optString("text", "");
                    if (text.isEmpty()) {
                        return LLMDecision.none(response);
                    }
                    action = InputInjectionService.InputAction.textInput(text);
                    break;
                }

                case "wait":
                    action = InputInjectionService.InputAction.waitAction(
                            json.optLong("durationMs", 1000));
                    break;

                default:
                    Log.w(TAG, "Unknown action type: " + actionType);
                    return LLMDecision.none(response);
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
            return LLMDecision.action(action, response);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing response", e);
            return LLMDecision.none(response);
        }
    }

    private static long clampMs(long value) {
        return Math.min(Math.max(value, 50), 10000);
    }

    public void release() {
        mExecutor.shutdown();
    }

    /**
     * Free the shared on-device model (it is otherwise kept resident between
     * requests). Called when the assistant service goes away.
     */
    public static void unloadLocalModel(Context context) {
        LocalInference local = LocalInference.get(context);
        if (local == null) {
            return;
        }
        // Closing waits for any in-flight generation; keep it off the caller.
        local.cancel();
        new Thread(local::close, "llm-local-close").start();
    }

    /** Callback for screen analysis. */
    public interface LLMCallback {
        void onDecision(LLMDecision decision);
        /** The in-flight request was cancelled by the user interrupt. */
        void onInterrupted();
        void onError(String error);
    }

    /** Callback for the connection test. */
    public interface TestCallback {
        void onResult(boolean success, String detail);
    }

    /**
     * What the model decided to do: perform an {@link #ACTION}, report the
     * task {@link #DONE} (with a summary) or {@link #NONE} (nothing useful
     * to do right now).
     */
    public static class LLMDecision {
        public enum Kind { ACTION, DONE, NONE }

        public final Kind kind;
        /** Non-null only for {@link Kind#ACTION}; coordinates in screen pixels. */
        public final InputInjectionService.InputAction action;
        /** One-sentence summary of what was achieved; only for {@link Kind#DONE}. */
        public final String summary;
        public final String raw;
        /**
         * How to describe the action in later prompts, in the model's own
         * coordinate space; null = use the screen-pixel description.
         */
        public final String historyEntry;

        private LLMDecision(Kind kind, InputInjectionService.InputAction action,
                String summary, String raw) {
            this(kind, action, summary, raw, null);
        }

        private LLMDecision(Kind kind, InputInjectionService.InputAction action,
                String summary, String raw, String historyEntry) {
            this.kind = kind;
            this.action = action;
            this.summary = summary;
            this.raw = raw;
            this.historyEntry = historyEntry;
        }

        LLMDecision withHistoryEntry(String entry) {
            return new LLMDecision(kind, action, summary, raw, entry);
        }

        public static LLMDecision action(InputInjectionService.InputAction action, String raw) {
            return new LLMDecision(Kind.ACTION, action, null, raw);
        }

        public static LLMDecision done(String summary, String raw) {
            return new LLMDecision(Kind.DONE, null, summary, raw);
        }

        public static LLMDecision none(String raw) {
            return new LLMDecision(Kind.NONE, null, null, raw);
        }
    }
}
