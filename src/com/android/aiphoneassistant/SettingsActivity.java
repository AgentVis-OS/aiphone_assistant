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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings UI: enable/disable the assistant, pick the backend (remote API or
 * on-device LiteRT-LM model), configure the LLM endpoint, model, API key and
 * capture interval, and — most importantly — the TASK the
 * assistant should work on. Without a task the assistant stays idle.
 */
public class SettingsActivity extends Activity {
    private static final String TAG = "AIPhoneAssistant.Settings";

    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o";
    /** DeepSeek exposes an OpenAI-compatible chat-completions endpoint with vision. */
    private static final String DEEPSEEK_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-flash";

    private SharedPreferences mPrefs;
    private Switch mEnableSwitch;
    private TextView mProviderText;
    private TextView mTaskText;
    private TextView mEndpointText;
    private TextView mModelText;
    private TextView mApiKeyText;
    private TextView mIntervalText;
    private TextView mBackendText;
    private TextView mModelPathText;
    private TextView mAccelText;
    private ChatStore mStore;

    private LLMClientService mTestClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_title);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mPrefs = LLMClientService.getPrefs(this);
        mStore = new ChatStore(this);
        createUI();
    }

    private void createUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 8);
        layout.addView(title);

        // The chat is the main surface now; settings is secondary.
        layout.addView(createButton(getString(R.string.action_back_to_chat), () -> finish()));
        addSpacer(layout);

        // Enable/Disable switch
        LinearLayout enableLayout = new LinearLayout(this);
        enableLayout.setOrientation(LinearLayout.HORIZONTAL);

        TextView enableLabel = new TextView(this);
        enableLabel.setText("Enable AI Assistant:");
        enableLabel.setTextSize(16);
        enableLabel.setPadding(0, 0, 16, 0);

        mEnableSwitch = new Switch(this);
        mEnableSwitch.setChecked(mPrefs.getBoolean(LLMClientService.KEY_ENABLED, false));
        mEnableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setEnabled(isChecked);
            Toast.makeText(this,
                    isChecked ? R.string.toast_service_enabled : R.string.toast_service_disabled,
                    Toast.LENGTH_SHORT).show();
        });

        enableLayout.addView(enableLabel);
        enableLayout.addView(mEnableSwitch);
        layout.addView(enableLayout);
        addSpacer(layout);

        // Task (what the assistant should do; empty = idle)
        mTaskText = createConfigRow(layout, "Task (what should the assistant do?)",
                taskDisplayValue());
        layout.addView(createButton("Change Task", () -> editTask()));
        addSpacer(layout);

        // Backend choice + on-device settings: only in builds that include
        // the LiteRT-LM backend (WITH_LITERTLM); otherwise remote-only.
        if (LocalInference.isAvailable()) {
            // Backend: remote OpenAI-compatible API or on-device LiteRT-LM model
            mBackendText = createConfigRow(layout, "Backend", backendDisplayValue());
            layout.addView(createButton("Change Backend", () -> editBackend()));
            addSpacer(layout);

            // On-device model file (.litertlm)
            mModelPathText = createConfigRow(layout, "On-device Model File",
                    LLMClientService.localModelPath(this));
            layout.addView(createButton("Change Model File", () ->
                    showEditTextDialog("On-device Model File",
                            LLMClientService.KEY_LOCAL_MODEL_PATH,
                            LLMClientService.localModelPath(this),
                            "Path to a .litertlm model readable by the app, e.g. "
                                    + "gemma-4-E2B-it.litertlm. Leave empty for the default.")));
            addSpacer(layout);

            // On-device accelerator
            mAccelText = createConfigRow(layout, "On-device Accelerator", accelDisplayValue());
            layout.addView(createButton("Change Accelerator", () -> editAccel()));
            addSpacer(layout);
        }

        // Provider preset: one tap fills endpoint + model for a known service
        mProviderText = createConfigRow(layout, "Provider", providerDisplayValue());
        layout.addView(createButton("Choose Provider", () -> chooseProvider()));
        addSpacer(layout);

        // Endpoint
        mEndpointText = createConfigRow(layout, "LLM Endpoint",
                mPrefs.getString(LLMClientService.KEY_ENDPOINT, DEFAULT_ENDPOINT));
        layout.addView(createButton("Change Endpoint", () ->
                showEditTextDialog("LLM Endpoint", LLMClientService.KEY_ENDPOINT,
                        DEFAULT_ENDPOINT, "OpenAI-compatible chat completions URL")));
        addSpacer(layout);

        // Model
        mModelText = createConfigRow(layout, "Model",
                mPrefs.getString(LLMClientService.KEY_MODEL, DEFAULT_MODEL));
        layout.addView(createButton("Change Model", () ->
                showEditTextDialog("Model Name", LLMClientService.KEY_MODEL,
                        DEFAULT_MODEL, "e.g. gpt-4o, glm-4v-plus, qwen-vl-max")));
        addSpacer(layout);

        // API key
        mApiKeyText = createConfigRow(layout, "API Key", maskApiKey(
                mPrefs.getString(LLMClientService.KEY_API_KEY, "")));
        layout.addView(createButton("Change API Key", () ->
                showEditTextDialog("API Key", LLMClientService.KEY_API_KEY, "",
                        "Leave empty for local servers without auth", true)));
        addSpacer(layout);

        // Capture interval
        mIntervalText = createConfigRow(layout, "Capture Interval (ms)",
                String.valueOf(mPrefs.getInt(LLMClientService.KEY_CAPTURE_INTERVAL,
                        LLMClientService.DEFAULT_CAPTURE_INTERVAL_MS)));
        layout.addView(createButton("Change Interval", () -> editCaptureInterval()));
        addSpacer(layout);

        // Test connection
        layout.addView(createButton("Test Connection", () -> testConnection()));

        // ScrollView so the form stays usable on small screens
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        // targetSdk 35 enforces edge-to-edge: without padding the first row
        // (the enable switch) draws underneath the status bar and cannot be
        // tapped at the bottom of the screen.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            scroll.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }

    private String providerDisplayValue() {
        String endpoint = mPrefs.getString(LLMClientService.KEY_ENDPOINT, DEFAULT_ENDPOINT);
        String e = endpoint == null ? "" : endpoint.trim().toLowerCase(java.util.Locale.ROOT);
        if (e.startsWith("https://api.deepseek.com")) {
            return "DeepSeek";
        }
        if (e.startsWith("https://api.openai.com")) {
            return "OpenAI";
        }
        return e.isEmpty() ? "(not set)" : "Custom";
    }

    /**
     * Fill endpoint + model for a known OpenAI-compatible provider. DeepSeek's
     * {@code deepseek-flash} accepts images in the same {@code image_url} shape
     * this app already sends, so no other setting has to change.
     */
    private void chooseProvider() {
        final String[] items = {
                "DeepSeek — deepseek-flash (vision)",
                "OpenAI — gpt-4o",
                "Custom — edit endpoint/model below",
        };
        new AlertDialog.Builder(this)
                .setTitle("Choose a provider")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        applyProvider(DEEPSEEK_ENDPOINT, DEEPSEEK_MODEL);
                    } else if (which == 1) {
                        applyProvider(DEFAULT_ENDPOINT, DEFAULT_MODEL);
                    }
                })
                .show();
    }

    private void applyProvider(String endpoint, String model) {
        mPrefs.edit()
                .putString(LLMClientService.KEY_ENDPOINT, endpoint)
                .putString(LLMClientService.KEY_MODEL, model)
                .apply();
        if (mEndpointText != null) {
            mEndpointText.setText(endpoint);
        }
        if (mModelText != null) {
            mModelText.setText(model);
        }
        if (mProviderText != null) {
            mProviderText.setText(providerDisplayValue());
        }
        Toast.makeText(this, "Provider set to " + model, Toast.LENGTH_SHORT).show();
    }

    private String taskDisplayValue() {
        String task = mPrefs.getString(LLMClientService.KEY_TASK, "");
        return task == null || task.trim().isEmpty() ? "(not set - assistant idles)" : task;
    }

    private String backendDisplayValue() {
        return LLMClientService.isLocalBackend(mPrefs)
                ? "On-device (LiteRT-LM)" : "Remote API (OpenAI-compatible)";
    }

    private String accelDisplayValue() {
        return LocalInference.ACCEL_CPU.equals(LLMClientService.localAccel(this))
                ? "CPU" : "GPU (falls back to CPU)";
    }

    private void editBackend() {
        String[] labels = {"Remote API (OpenAI-compatible)", "On-device (LiteRT-LM)"};
        String[] values = {LLMClientService.BACKEND_REMOTE, LLMClientService.BACKEND_LOCAL};
        int checked = LLMClientService.isLocalBackend(mPrefs) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Backend")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    mPrefs.edit().putString(LLMClientService.KEY_BACKEND, values[which]).apply();
                    updateDisplay();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void editAccel() {
        String[] labels = {"GPU (falls back to CPU)", "CPU"};
        String[] values = {LocalInference.ACCEL_GPU, LocalInference.ACCEL_CPU};
        int checked = LocalInference.ACCEL_CPU.equals(LLMClientService.localAccel(this))
                ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("On-device Accelerator")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    mPrefs.edit().putString(LLMClientService.KEY_LOCAL_ACCEL, values[which])
                            .apply();
                    updateDisplay();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private TextView createConfigRow(LinearLayout layout, String label, String value) {
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setPadding(0, 16, 0, 4);
        layout.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(14);
        valueView.setPadding(16, 0, 0, 8);
        layout.addView(valueView);

        return valueView;
    }

    private Button createButton(String text, Runnable onClick) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    private void addSpacer(LinearLayout layout) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16));
        layout.addView(spacer);
    }

    private void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(LLMClientService.KEY_ENABLED, enabled).apply();

        Intent serviceIntent = new Intent(this, AIPhoneAssistantService.class);
        if (enabled) {
            // Arming alone does not run anything: only a pending task activates
            // the assistant. The chat is the primary way to send one.
            String state = ChatStore.getTaskState(this);
            if (ChatStore.STATE_PENDING.equals(state)
                    && !ChatStore.getTask(this).isEmpty()) {
                serviceIntent.setAction(AIPhoneAssistantService.ACTION_ACTIVATE);
                startForegroundService(serviceIntent);
            }
        } else {
            // Master off also aborts anything in flight or armed.
            String state = ChatStore.getTaskState(this);
            if (ChatStore.STATE_EXECUTING.equals(state)
                    || ChatStore.STATE_PAUSED.equals(state)
                    || ChatStore.STATE_PENDING.equals(state)) {
                ChatStore.setTaskState(this, ChatStore.STATE_STOPPED);
            }
            stopService(serviceIntent);
        }
    }

    private void editTask() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Task");
        builder.setMessage("Describe what the assistant should do on the current screen, "
                + "e.g. \"Open the browser and search for the weather\". "
                + "Saving activates the assistant (the chat window is the primary entry).");

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        input.setText(mPrefs.getString(LLMClientService.KEY_TASK, ""));
        builder.setView(input);

        builder.setPositiveButton(R.string.action_save, (dialog, which) -> {
            String task = input.getText().toString().trim();
            if (!task.isEmpty()) {
                mStore.createConversation(this, task);
                ChatStore.addRecentTask(this, task);
                if (mPrefs.getBoolean(LLMClientService.KEY_ENABLED, false)) {
                    Intent serviceIntent = new Intent(this, AIPhoneAssistantService.class);
                    serviceIntent.setAction(AIPhoneAssistantService.ACTION_ACTIVATE);
                    startForegroundService(serviceIntent);
                }
            }
            updateDisplay();
        });
        builder.setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void editCaptureInterval() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Capture Interval (ms)");
        builder.setMessage("How often to check the screen (200-2000 ms)");

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(mPrefs.getInt(LLMClientService.KEY_CAPTURE_INTERVAL,
                LLMClientService.DEFAULT_CAPTURE_INTERVAL_MS)));
        builder.setView(input);

        builder.setPositiveButton(R.string.action_save, (dialog, which) -> {
            try {
                int value = Integer.parseInt(input.getText().toString().trim());
                value = Math.min(Math.max(value, 100), 5000);
                mPrefs.edit().putInt(LLMClientService.KEY_CAPTURE_INTERVAL, value).apply();
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid number", Toast.LENGTH_SHORT).show();
            }
            updateDisplay();
        });
        builder.setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showEditTextDialog(String title, String key, String defaultValue,
            String message) {
        showEditTextDialog(title, key, defaultValue, message, false);
    }

    private void showEditTextDialog(String title, String key, String defaultValue,
            String message, boolean password) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);

        EditText input = new EditText(this);
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        input.setText(mPrefs.getString(key, defaultValue));
        builder.setView(input);

        builder.setPositiveButton(R.string.action_save, (dialog, which) -> {
            mPrefs.edit().putString(key, input.getText().toString().trim()).apply();
            updateDisplay();
        });
        builder.setNegativeButton(R.string.action_cancel, (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void updateDisplay() {
        mTaskText.setText(taskDisplayValue());
        if (mBackendText != null) {
            mBackendText.setText(backendDisplayValue());
            mModelPathText.setText(LLMClientService.localModelPath(this));
            mAccelText.setText(accelDisplayValue());
        }
        mEndpointText.setText(mPrefs.getString(LLMClientService.KEY_ENDPOINT,
                mEndpointText.getText().toString()));
        mModelText.setText(mPrefs.getString(LLMClientService.KEY_MODEL,
                mModelText.getText().toString()));
        mApiKeyText.setText(maskApiKey(mPrefs.getString(LLMClientService.KEY_API_KEY, "")));
        mIntervalText.setText(String.valueOf(mPrefs.getInt(LLMClientService.KEY_CAPTURE_INTERVAL,
                LLMClientService.DEFAULT_CAPTURE_INTERVAL_MS)));
    }

    private String maskApiKey(String key) {
        if (key == null || key.isEmpty()) {
            return "(not set)";
        }
        if (key.length() > 8) {
            return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }
        return "****";
    }

    private void testConnection() {
        Toast.makeText(this, R.string.toast_testing_connection, Toast.LENGTH_SHORT).show();
        if (mTestClient == null) {
            mTestClient = new LLMClientService(this);
        }
        mTestClient.testConnection((success, detail) ->
                Toast.makeText(this,
                        (success
                                ? getString(R.string.toast_connection_success)
                                : getString(R.string.toast_connection_failed)) + "\n" + detail,
                        Toast.LENGTH_LONG)
                        .show());
    }

    @Override
    protected void onDestroy() {
        if (mTestClient != null) {
            mTestClient.release();
            mTestClient = null;
        }
        super.onDestroy();
    }
}
