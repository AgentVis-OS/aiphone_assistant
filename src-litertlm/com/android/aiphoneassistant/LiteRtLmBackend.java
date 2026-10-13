/*
 * Copyright (C) 2026 The LineageOS Project
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
import android.os.SystemClock;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.ResponseFormat;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.ai.edge.litertlm.ThinkingConfig;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * On-device inference through LiteRT-LM (e.g. Gemma 4 E2B, a .litertlm file).
 *
 * The engine is loaded lazily on the first request and kept resident (loading
 * takes seconds and ~1-3 GB of RAM); it is reloaded when the model path or
 * accelerator preference changes. Each request runs in a fresh Conversation
 * because the assistant's prompt is self-contained (task + recent actions +
 * screenshot), so no chat history must leak between rounds.
 *
 * One instance per process ({@link #get}) so the Settings test and the
 * assistant service share a single loaded model instead of holding two.
 *
 * Only compiled into builds with LiteRT-LM enabled; the core app reaches it
 * through {@link LocalInference} (by class name, so keep the name and the
 * static {@code get(Context)} in sync with LocalInference.Holder).
 *
 * All methods block and must be called off the main thread, except
 * {@link #cancel()}.
 */
class LiteRtLmBackend implements LocalInference {
    private static final String TAG = "AIPhoneAssistant.Local";

    // KV-cache budget: prompt (~400 tokens) + one image (~256-280 tokens) +
    // the short JSON answer fit comfortably.
    private static final int MAX_NUM_TOKENS = 4096;
    private static final int MAX_OUTPUT_TOKENS = 256;

    private final Context mContext;
    private final Object mLock = new Object();
    private Engine mEngine;
    private String mEngineKey;
    private String mEngineAccel;
    private final AtomicReference<Conversation> mActive = new AtomicReference<>();
    private volatile boolean mCancelled;

    private static LiteRtLmBackend sInstance;

    static synchronized LiteRtLmBackend get(Context context) {
        if (sInstance == null) {
            sInstance = new LiteRtLmBackend(context);
        }
        return sInstance;
    }

    private LiteRtLmBackend(Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Run one prompt (optionally with a JPEG/PNG image) and return the full
     * text answer. A non-null {@code jsonSchema} constrains decoding so the
     * answer is always a JSON object matching it (small models otherwise
     * emit malformed JSON or invent fields).
     */
    @Override
    public String generate(String modelPath, String accel, String prompt, byte[] image,
            String jsonSchema) throws IOException {
        synchronized (mLock) {
            Engine engine = ensureEngine(modelPath, accel);
            ConversationConfig config = new ConversationConfig(
                    null,                                   // systemInstruction
                    Collections.<Message>emptyList(),       // initialMessages
                    Collections.emptyList(),                // tools
                    new SamplerConfig(1, 1.0, 0.0, 0),      // greedy: deterministic actions
                    false,                                  // automaticToolCalling
                    null,                                   // channels
                    Collections.<String, Object>emptyMap(), // extraContext
                    null,                                   // loraConfig
                    false,                                  // prefillPrefaceOnInit
                    MAX_OUTPUT_TOKENS,                      // maxOutputToken
                    new ThinkingConfig(false),              // no hidden reasoning
                    jsonSchema != null,                     // enableResponseFormat
                    null,                                   // enableSpeculativeDecoding
                    null);                                  // chatTemplate
            Conversation conversation;
            try {
                conversation = engine.createConversation(config);
            } catch (RuntimeException e) {
                throw new IOException("Failed to create conversation: " + e.getMessage(), e);
            }
            mCancelled = false;
            mActive.set(conversation);
            try {
                Contents contents = image != null
                        ? Contents.Companion.of(new Content.ImageBytes(image),
                                new Content.Text(prompt))
                        : Contents.Companion.of(prompt);
                ResponseFormat format = jsonSchema != null
                        ? ResponseFormat.json(jsonSchema) : null;
                return await(conversation, contents, format);
            } finally {
                mActive.compareAndSet(conversation, null);
                conversation.close();
            }
        }
    }

    /** Stream the answer through the callback API and block until done. */
    private String await(Conversation conversation, Contents contents, ResponseFormat format)
            throws IOException {
        StringBuilder text = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        long t0 = SystemClock.elapsedRealtime();
        conversation.sendMessageAsync(contents, new MessageCallback() {
            @Override
            public void onMessage(Message message) {
                synchronized (text) {
                    for (Content c : message.getContents().getContents()) {
                        if (c instanceof Content.Text) {
                            text.append(((Content.Text) c).getText());
                        }
                    }
                }
            }

            @Override
            public void onDone() {
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                done.countDown();
            }
        }, Collections.<String, Object>emptyMap(), null, null, null,
                null /* maxTokens: conversation default */, null, format);
        try {
            // cancelProcess() should end the stream through onDone/onError;
            // don't hang forever if the native side never reports back.
            long cancelAt = 0;
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (mCancelled) {
                    if (cancelAt == 0) {
                        cancelAt = SystemClock.elapsedRealtime();
                    } else if (SystemClock.elapsedRealtime() - cancelAt > 10000) {
                        throw new IOException("Cancelled (no acknowledgement from engine)");
                    }
                }
            }
        } catch (InterruptedException e) {
            conversation.cancelProcess();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        if (mCancelled) {
            throw new IOException("Cancelled");
        }
        Log.i(TAG, "Generation took " + (SystemClock.elapsedRealtime() - t0) + " ms");
        if (error.get() != null) {
            throw new IOException("Local inference failed: " + error.get().getMessage(),
                    error.get());
        }
        synchronized (text) {
            return text.toString().trim();
        }
    }

    private Engine ensureEngine(String modelPath, String accel) throws IOException {
        String key = modelPath + "|" + accel;
        if (mEngine != null && key.equals(mEngineKey)) {
            return mEngine;
        }
        closeEngineLocked();
        File model = new File(modelPath);
        if (!model.canRead()) {
            throw new IOException("Model file not readable: " + modelPath);
        }
        long t0 = SystemClock.elapsedRealtime();
        if (ACCEL_GPU.equals(accel)) {
            try {
                mEngine = createEngine(modelPath, new Backend.GPU(), new Backend.GPU());
                mEngineAccel = ACCEL_GPU;
            } catch (RuntimeException e) {
                Log.w(TAG, "GPU backend failed, falling back to CPU", e);
            }
        }
        if (mEngine == null) {
            try {
                mEngine = createEngine(modelPath, new Backend.CPU(), new Backend.CPU());
                mEngineAccel = ACCEL_CPU;
            } catch (RuntimeException e) {
                throw new IOException("Failed to load model: " + e.getMessage(), e);
            }
        }
        mEngineKey = key;
        Log.i(TAG, "Loaded " + model.getName() + " on " + mEngineAccel + " in "
                + (SystemClock.elapsedRealtime() - t0) + " ms");
        return mEngine;
    }

    private Engine createEngine(String modelPath, Backend backend, Backend vision) {
        EngineConfig config = new EngineConfig(modelPath, backend, vision,
                null /* audio */, MAX_NUM_TOKENS, 1 /* maxNumImages */,
                mContext.getCacheDir().getPath());
        Engine engine = new Engine(config);
        try {
            engine.initialize();
        } catch (RuntimeException e) {
            engine.close();
            throw e;
        }
        return engine;
    }

    @Override
    public String loadedAccel() {
        return mEngineAccel;
    }

    @Override
    public void cancel() {
        mCancelled = true;
        Conversation conversation = mActive.get();
        if (conversation != null) {
            conversation.cancelProcess();
        }
    }

    @Override
    public void close() {
        cancel();
        synchronized (mLock) {
            closeEngineLocked();
        }
    }

    private void closeEngineLocked() {
        if (mEngine != null) {
            try {
                mEngine.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Engine close failed", e);
            }
            mEngine = null;
            mEngineKey = null;
            mEngineAccel = null;
        }
    }
}
