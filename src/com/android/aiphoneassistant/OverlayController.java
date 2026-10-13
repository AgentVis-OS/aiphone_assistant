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

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Controls the user-facing overlay windows.
 *
 * 1. The full-screen effect overlay ({@link OverlayView}) for the
 *    thinking/acting signals. As a platform-signed app running as system UID
 *    (and holding SYSTEM_ALERT_WINDOW), it can use
 *    {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY} with no extra
 *    permission. The window is non-touchable so injected input events pass
 *    through it to the app underneath.
 *
 * 2. The interrupt bar ({@link InterruptBarView}): a semi-transparent pill
 *    over the middle of the status bar, shown while a task executes. It MUST
 *    use TYPE_STATUS_BAR_ADDITIONAL (layer above the status bar — a regular
 *    application overlay would never receive touches in that strip) which
 *    requires the INTERNAL_SYSTEM_WINDOW permission held via the platform
 *    signature. Unlike the effect overlay it IS touchable; while the
 *    assistant injects input the service temporarily flips it to
 *    FLAG_NOT_TOUCHABLE so model taps pass through.
 *
 * 3. The stop-confirmation dialog ({@link ConfirmDialogView}) drawn inside
 *    the overlay layer so the paused task's screen stays untouched underneath.
 *
 * All window/view operations are marshalled onto the supplied UI handler, since window
 * mutations must happen on the thread that owns the view. Callers (e.g. the capture
 * thread) may invoke the public methods from any thread.
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

    private InterruptBarView mBarView;
    private WindowManager.LayoutParams mBarParams;
    private volatile boolean mBarVisible = false;
    private volatile boolean mBarTouchable = true;

    private ConfirmDialogView mDialogView;
    private volatile boolean mDialogVisible = false;

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

    // ------------------------------------------------------------------
    // Interrupt bar (status-bar pill)
    // ------------------------------------------------------------------

    /** Show the interrupt bar, replacing any previous listener. */
    public void showInterruptBar(InterruptBarView.Listener listener) {
        mUiHandler.post(() -> showInterruptBarInternal(listener));
    }

    public void hideInterruptBar() {
        mUiHandler.post(this::hideInterruptBarInternal);
    }

    /**
     * Temporarily make the bar pass touches through (while the assistant
     * injects input near the top of the screen), then restore it.
     */
    public void setInterruptBarTouchable(final boolean touchable) {
        mUiHandler.post(() -> setInterruptBarTouchableInternal(touchable));
    }

    public boolean isInterruptBarVisible() {
        return mBarVisible;
    }

    // ------------------------------------------------------------------
    // Stop-confirmation dialog
    // ------------------------------------------------------------------

    /** Show the "continue or stop?" dialog for the given task. */
    public void showConfirmDialog(String taskText, ConfirmDialogView.Listener listener) {
        mUiHandler.post(() -> showConfirmDialogInternal(taskText, listener));
    }

    public void hideConfirmDialog() {
        mUiHandler.post(this::hideConfirmDialogInternal);
    }

    // ------------------------------------------------------------------
    // Internals (UI thread only)
    // ------------------------------------------------------------------

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

    private void showInterruptBarInternal(InterruptBarView.Listener listener) {
        // Always a fresh view so the tap sequence starts clean; replace any
        // bar that is already on screen.
        if (mBarView != null && mBarVisible) {
            try {
                mWindowManager.removeView(mBarView);
            } catch (Exception e) {
                Log.w(TAG, "Failed to replace interrupt bar", e);
            }
            mBarVisible = false;
        }
        mBarView = new InterruptBarView(mContext, listener);
        mBarParams = buildBarParams();
        mBarTouchable = true;
        try {
            mWindowManager.addView(mBarView, mBarParams);
            mBarVisible = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add interrupt bar", e);
        }
    }

    private WindowManager.LayoutParams buildBarParams() {
        float density = mContext.getResources().getDisplayMetrics().density;
        int screenW = mContext.getResources().getDisplayMetrics().widthPixels;
        int barHeight = statusBarHeight(density);
        int barWidth = Math.max((int) (120 * density), screenW / 3);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                barWidth,
                barHeight,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 0;
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return params;
    }

    private int statusBarHeight(float density) {
        int resId = mContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            try {
                return mContext.getResources().getDimensionPixelSize(resId);
            } catch (Exception ignored) {
            }
        }
        return (int) (28 * density);
    }

    private void hideInterruptBarInternal() {
        if (mBarView != null && mBarVisible) {
            try {
                mWindowManager.removeView(mBarView);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove interrupt bar", e);
            }
        }
        mBarVisible = false;
        mBarTouchable = true;
    }

    private void setInterruptBarTouchableInternal(boolean touchable) {
        if (mBarView == null || !mBarVisible || mBarTouchable == touchable) {
            mBarTouchable = touchable;
            return;
        }
        mBarTouchable = touchable;
        if (touchable) {
            mBarParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mBarParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            mWindowManager.updateViewLayout(mBarView, mBarParams);
        } catch (Exception e) {
            Log.w(TAG, "Failed to toggle interrupt bar touchability", e);
        }
    }

    private void showConfirmDialogInternal(String taskText, ConfirmDialogView.Listener listener) {
        hideConfirmDialogInternal();
        mDialogView = new ConfirmDialogView(mContext, taskText, listener);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            mWindowManager.addView(mDialogView, params);
            mDialogVisible = true;
            Log.i(TAG, "Confirm dialog shown");
            // The dialog replaces the bar visually; drop it while paused.
            hideInterruptBarInternal();
        } catch (Exception e) {
            Log.e(TAG, "Failed to add confirm dialog", e);
            mDialogView = null;
        }
    }

    private void hideConfirmDialogInternal() {
        if (mDialogView != null && mDialogVisible) {
            try {
                mWindowManager.removeView(mDialogView);
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove confirm dialog", e);
            }
        }
        mDialogView = null;
        mDialogVisible = false;
    }

    /** Tear down all overlay windows and cancel pending callbacks. */
    public void release() {
        mUiHandler.removeCallbacksAndMessages(null);
        hideInternal();
        hideInterruptBarInternal();
        hideConfirmDialogInternal();
        mView = null;
        mBarView = null;
        mDialogView = null;
    }
}
