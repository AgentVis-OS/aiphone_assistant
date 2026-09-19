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

import android.app.Application;
import android.util.Log;

/**
 * AI Phone Assistant Application class
 *
 * This is the main application class that initializes the AI Phone Assistant
 * system service. The app runs as a privileged system app with signature-level
 * permissions to enable background screen capture and input injection.
 */
public class AIPhoneAssistantApplication extends Application {
    private static final String TAG = "AIPhoneAssistantApp";

    private static AIPhoneAssistantApplication sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "AI Phone Assistant Application initialized");
        Log.i(TAG, "Running with system UID: " + android.os.Process.myUid());
    }

    public static AIPhoneAssistantApplication getInstance() {
        return sInstance;
    }
}
