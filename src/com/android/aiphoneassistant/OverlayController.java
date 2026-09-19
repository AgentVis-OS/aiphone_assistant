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
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Controls the user-facing overlay window.
 *
 * Manages a single full-screen {@link OverlayView} added to the {@link WindowManager}.
 * As a platform-signed app running as system UID (and holding SYSTEM_ALERT_WINDOW), it can
 * use {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} with no extra permission.
 *
 * All window/view operations are marshalled onto the supplied UI handler, since window
 * mutations must happen on the thread that owns the view. Callers (e.g. the capture
 * thread) may invoke the public methods from any thread.
 *
 * The window is non-touchable ({@link WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE}) so
 * injected input events pass through it to the app underneath.
 */
public class OverlayController {
    private static final String TAG = "AIPhoneAssistant.Overlay";
    private static final boolean DEBUG = true;

    private final Context mContext;
    private final Handler mUiHandler;
    private final WindowManager mWindowManager;

    private OverlayView mView;
    private WindowManager.LayoutParams mParams;
    private volatile boolean mVisible = false;

    public OverlayController(Context context, Handler uiHandler) {
        mContext = context;
        mUiHandler = uiHandler;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Show the "thinking" effect while the LLM is analyzing. */
    public void showThinking() {
        mUiHandler.post(this::showThinkingInternal);
    }

    /** Show the "acting" ripple at the given screen coordinates. */
    public void showActing(final float x, final float y) {
        mUiHandler.post(() -> showActingInternal(x, y));
    }

    /** Remove the overlay immediately. */
    public void hide() {
        mUiHandler.post(this::hideInternal);
    }

    /** Remove the overlay after a short delay, letting the acting marker finish. */
    public void hideDelayed(long delayMs) {
        mUiHandler.postDelayed(this::hideInternal, delayMs);
    }

    /** True while the overlay window is on screen (readable from any thread). */
    public boolean isVisible() {
        return mVisible;
    }

    private void ensureWindow() {
        if (mView != null) {
            return;
        }
        mView = new OverlayView(mContext);
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = Gravity.TOP | Gravity.LEFT;
    }

    private void addIfAbsent() {
        ensureWindow();
        if (mVisible) {
            return;
        }
        try {
            mWindowManager.addView(mView, mParams);
            mVisible = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add overlay view", e);
        }
    }

    private void showThinkingInternal() {
        addIfAbsent();
        if (mView != null) {
            mView.showThinking();
        }
    }

    private void showActingInternal(float x, float y) {
        addIfAbsent();
        if (mView != null) {
            mView.showActing(x, y);
        }
    }

    private void hideInternal() {
        if (mView != null && mVisible) {
            try {
                mView.hide();
                mWindowManager.removeView(mView);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove overlay view", e);
            }
        }
        mVisible = false;
    }

    /** Tear down the overlay and cancel pending hide callbacks. */
    public void release() {
        mUiHandler.removeCallbacksAndMessages(null);
        hideInternal();
        mView = null;
    }
}
