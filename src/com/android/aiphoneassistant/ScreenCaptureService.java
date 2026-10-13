/*
 * Copyright (C) 2024 The AgentVisOS Project
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

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.window.ScreenCapture;

/**
 * Screen capture via the platform's WindowManager capture API.
 *
 * This app is built with platform_apis: true and signed with the platform
 * certificate, so it calls the @hide capture API directly — no reflection.
 * The SurfaceControl.screenshot() overloads that older versions of this code
 * looked up via reflection were removed from the framework in Android 12+;
 * on Android 13..15 the supported path for a platform app is
 * IWindowManager.captureDisplay(), which enforces READ_FRAME_BUFFER
 * (granted via the privapp-permissions XML).
 *
 * As a fallback (only if the WindowManager path fails), a mirror VirtualDisplay
 * feeding an ImageReader is created lazily by the caller; this class only
 * implements the primary path and keeps display metrics for the rest of the app.
 */
public class ScreenCaptureService {
    private static final String TAG = "AIPhoneAssistant.ScreenCap";

    private final AIPhoneAssistantService mParentService;
    private boolean mInitialized = false;

    // Display info (filled in by initialize(); 0 = unknown)
    private int mDisplayWidth = 0;
    private int mDisplayHeight = 0;
    private int mDisplayDensityDpi = 0;

    public ScreenCaptureService(AIPhoneAssistantService parent) {
        mParentService = parent;
    }

    /**
     * Refresh display metrics. Cheap, no resources held.
     */
    public void initialize() {
        updateDisplayInfo();
        mInitialized = true;
        Log.i(TAG, "ScreenCaptureService initialized: " + mDisplayWidth + "x" + mDisplayHeight
                + " @" + mDisplayDensityDpi);
    }

    private void updateDisplayInfo() {
        try {
            DisplayManager displayManager = mParentService.getSystemService(DisplayManager.class);
            Display display = displayManager != null
                    ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display != null) {
                Point size = new Point();
                display.getRealSize(size);
                mDisplayWidth = size.x;
                mDisplayHeight = size.y;
                DisplayMetrics metrics = new DisplayMetrics();
                display.getMetrics(metrics);
                mDisplayDensityDpi = metrics.densityDpi;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get display info, using defaults", e);
        }
    }

    /**
     * Capture the current contents of the default display.
     *
     * @return a software ARGB_8888 bitmap, or null if capture failed
     */
    public Bitmap captureFrame() {
        if (!mInitialized) {
            initialize();
        }
        try {
            IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
            // Raw builder type: the framework's CaptureArgs.Builder is generic for
            // subclassing; plain CaptureArgs need no extra arguments.
            @SuppressLint("unchecked")
            ScreenCapture.CaptureArgs args = new ScreenCapture.CaptureArgs.Builder().build();
            ScreenCapture.SynchronousScreenCaptureListener listener =
                    ScreenCapture.createSyncCaptureListener();
            wms.captureDisplay(Display.DEFAULT_DISPLAY, args, listener);

            ScreenCapture.ScreenshotHardwareBuffer buffer = listener.getBuffer();
            if (buffer == null) {
                return null;
            }
            Bitmap hardwareBitmap = buffer.asBitmap();
            if (hardwareBitmap == null) {
                return null;
            }
            // asBitmap() wraps a HardwareBuffer; copy to a software bitmap so that
            // later pixel access (scaling, diffing, JPEG encode) works everywhere.
            Bitmap software = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            hardwareBitmap.recycle();
            return software;
        } catch (SecurityException e) {
            Log.e(TAG, "Not allowed to capture display - check READ_FRAME_BUFFER grant", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error capturing frame", e);
            return null;
        }
    }

    /** Display width in real pixels. */
    public int getDisplayWidth() {
        return mDisplayWidth;
    }

    /** Display height in real pixels. */
    public int getDisplayHeight() {
        return mDisplayHeight;
    }

    /** Display density DPI. */
    public int getDisplayDensityDpi() {
        return mDisplayDensityDpi;
    }

    /** Whether display metrics have been loaded. */
    public boolean isInitialized() {
        return mInitialized;
    }

    /** No persistent resources; kept for symmetric lifecycle. */
    public void release() {
        Log.i(TAG, "Releasing ScreenCaptureService");
        mInitialized = false;
    }
}
