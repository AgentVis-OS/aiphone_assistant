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

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.util.Log;
import android.view.Display;

import java.util.List;
import java.util.Set;

/**
 * Tells whether the foreground activity belongs to a package whose windows
 * ignore touches while another untrusted window (our effect overlay) covers
 * them. The permission dialog is the case that matters: PermissionController
 * drops the touch, so the overlay must be removed before injecting there.
 *
 * Runs as the system UID (platform signature), which passes the task-list
 * permission check, so no extra manifest permission is needed.
 */
final class ForegroundProbe {
    private static final String TAG = "AIPhoneAssistant.Probe";

    private static final Set<String> TOUCH_PROTECTED_PACKAGES = Set.of(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller");

    private ForegroundProbe() {
    }

    /** Package of the top activity on the default display, or null if unknown. */
    static String topPackage() {
        try {
            List<ActivityManager.RunningTaskInfo> tasks = ActivityTaskManager.getService()
                    .getTasks(1, false, false, Display.DEFAULT_DISPLAY);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            ComponentName top = tasks.get(0).topActivity;
            return top != null ? top.getPackageName() : null;
        } catch (Exception e) {
            Log.w(TAG, "Could not read the foreground task", e);
            return null;
        }
    }

    /**
     * True when the overlay must not be on screen while injecting. An unknown
     * foreground (probe failure) counts as protected: hiding is always safe.
     */
    static boolean isTouchProtected(String topPackage) {
        return topPackage == null || TOUCH_PROTECTED_PACKAGES.contains(topPackage);
    }
}
