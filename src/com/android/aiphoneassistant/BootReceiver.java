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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Starts the assistant service after boot, but only when the user has
 * explicitly enabled it in Settings. The default is disabled, so a freshly
 * flashed ROM does not start capturing and calling out to the network.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "AIPhoneAssistant.Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = LLMClientService.getPrefs(context);
        if (!prefs.getBoolean(LLMClientService.KEY_ENABLED, false)) {
            Log.i(TAG, "Assistant disabled; not starting on boot");
            return;
        }
        // Only boot-start when a task was armed but not yet executed. Finished
        // tasks are never re-run just because the device rebooted.
        String state = ChatStore.getTaskState(context);
        if (!ChatStore.STATE_PENDING.equals(state)) {
            Log.i(TAG, "No pending task (state=" + state + "); not starting on boot");
            return;
        }

        Log.i(TAG, "Device boot completed with pending task - starting service");
        try {
            Intent serviceIntent = new Intent(context, AIPhoneAssistantService.class);
            serviceIntent.setAction(AIPhoneAssistantService.ACTION_ACTIVATE);
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AI Phone Assistant service", e);
        }
    }
}
