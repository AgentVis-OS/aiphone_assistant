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
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Optional on-device LLM backend.
 *
 * The implementation ({@code LiteRtLmBackend}, LiteRT-LM) lives in
 * {@code src-litertlm/} and is only compiled in when the build enables it
 * (WITH_LITERTLM=1 for tools/build-apk.sh, the Soong config variable
 * aiphoneassistant.with_litertlm for tree builds). It is looked up by name so
 * the core app has no compile-time dependency on LiteRT-LM; without it
 * {@link #get} returns null and the app is remote-only.
 */
interface LocalInference {
    String ACCEL_GPU = "gpu";
    String ACCEL_CPU = "cpu";

    /**
     * Run one prompt (optionally with a JPEG image) and return the full text
     * answer. A non-null {@code jsonSchema} constrains decoding to it.
     * Blocks; call off the main thread.
     */
    String generate(String modelPath, String accel, String prompt, byte[] image,
            String jsonSchema) throws IOException;

    /** Accelerator actually in use ("gpu"/"cpu"), or null before loading. */
    String loadedAccel();

    /** Abort the in-flight generation; safe to call from any thread. */
    void cancel();

    /** Free the loaded model. Blocks until any in-flight generation ends. */
    void close();

    /** Default model location inside the app's private storage. */
    static File defaultModelFile(Context context) {
        return new File(new File(context.getFilesDir(), "models"), "gemma-4-E2B-it.litertlm");
    }

    /** Whether this build includes an on-device backend. */
    static boolean isAvailable() {
        return Holder.IMPL_CLASS != null;
    }

    /** The process-wide backend, or null if this build has none. */
    static LocalInference get(Context context) {
        if (Holder.IMPL_CLASS == null) {
            return null;
        }
        try {
            return (LocalInference) Holder.IMPL_CLASS
                    .getDeclaredMethod("get", Context.class).invoke(null, context);
        } catch (ReflectiveOperationException e) {
            Log.e("AIPhoneAssistant.Local", "On-device backend failed to initialize", e);
            return null;
        }
    }

    /** Lazy, one-time lookup of the optional implementation class. */
    final class Holder {
        static final Class<?> IMPL_CLASS = find();

        private static Class<?> find() {
            try {
                return Class.forName("com.android.aiphoneassistant.LiteRtLmBackend");
            } catch (ClassNotFoundException e) {
                return null;
            }
        }

        private Holder() {}
    }
}
