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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main AI Phone Assistant Service.
 *
 * Orchestrates the see → think → act loop on a background capture thread:
 *   1. Captures the screen (WindowManager captureDisplay, with a mirror
 *      VirtualDisplay fallback).
 *   2. When {@link FrameChangeDetector} decides a settled, meaningful change
 *      happened, sends the frame to the configured LLM with the user's task.
 *   3. Injects the returned action (touch/key) via InputManager.
 *
 * Analysis only runs when both an endpoint and a task are configured; until
 * then the loop idles cheaply. The service is started/stopped explicitly from
 * SettingsActivity or BootReceiver (when the user has enabled it).
 */
public class AIPhoneAssistantService extends Service {
    private static final String TAG = "AIPhoneAssistant";

    private static final String NOTIFICATION_CHANNEL_ID = "ai_phone_assistant";
    private static final int NOTIFICATION_ID = 1001;

    // How long to leave the "acting" overlay up after injecting an action
    private static final long ACT_HIDE_DELAY_MS = 400;
    // Only log the first of a run of consecutive capture failures
    private static final int CAPTURE_FAILURE_LOG_THRESHOLD = 3;

    // Stale-task watchdog: when a task is configured but nothing has been
    // escalated for STALE_TASK_REANALYZE_MS (e.g. the model returned the
    // screen to an already-analyzed state), force a re-analysis so the model
    // gets another chance. Bounded by MAX_FORCED_REANALYSES consecutive
    // forced rounds: once the task is genuinely finished the assistant must
    // stay quiet instead of re-prompting forever; a natural (change-driven)
    // escalation refills the budget.
    private static final long STALE_TASK_REANALYZE_MS = 20000;
    private static final int MAX_FORCED_REANALYSES = 3;
    private long mLastEscalationAtMs;
    private int mForcedReanalyses = 0;

    private final IBinder mBinder = new AIPhoneAssistantBinder();
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final AtomicBoolean mAnalyzing = new AtomicBoolean(false);

    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private Handler mMainHandler;

    // Screen capture components (fallback path only)
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;

    // Subsystems
    private ScreenCaptureService mScreenCaptureService;
    private InputInjectionService mInputInjectionService;
    private LLMClientService mLLMClientService;
    private FrameChangeDetector mFrameDetector;
    private OverlayController mOverlayController;

    private int mConsecutiveCaptureFailures = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AI Phone Assistant Service created");

        mMainHandler = new Handler(Looper.getMainLooper());

        mCaptureThread = new HandlerThread("AIPhoneAssistant-Capture");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        mScreenCaptureService = new ScreenCaptureService(this);
        mInputInjectionService = new InputInjectionService(this);
        mLLMClientService = new LLMClientService(this);
        mFrameDetector = new FrameChangeDetector();
        mOverlayController = new OverlayController(this, mMainHandler);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "AI Phone Assistant Service started");
        startForeground(NOTIFICATION_ID, createNotification());

        if (mIsRunning.compareAndSet(false, true)) {
            mLastEscalationAtMs = System.currentTimeMillis();
            mForcedReanalyses = 0;
            mCaptureHandler.post(() -> mScreenCaptureService.initialize());
            mCaptureHandler.post(mCaptureRunnable);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "AI Phone Assistant Service stopping");
        mIsRunning.set(false);

        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
        releaseScreenCapture();

        if (mScreenCaptureService != null) {
            mScreenCaptureService.release();
        }
        if (mInputInjectionService != null) {
            mInputInjectionService.release();
        }
        if (mLLMClientService != null) {
            mLLMClientService.release();
        }
        if (mOverlayController != null) {
            mOverlayController.release();
        }

        if (mCaptureThread != null) {
            mCaptureThread.quitSafely();
            try {
                mCaptureThread.join();
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping capture thread", e);
            }
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Main loop: capture at the configured interval and escalate to the LLM
     * when the frame detector decides the change is worth analyzing.
     */
    private final Runnable mCaptureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsRunning.get()) {
                return;
            }

            SharedPreferences prefs = LLMClientService.getPrefs(AIPhoneAssistantService.this);
            int intervalMs = prefs.getInt(LLMClientService.KEY_CAPTURE_INTERVAL,
                    LLMClientService.DEFAULT_CAPTURE_INTERVAL_MS);
            boolean configured = !prefs.getString(LLMClientService.KEY_ENDPOINT, "").trim().isEmpty()
                    && !prefs.getString(LLMClientService.KEY_TASK, "").trim().isEmpty();

            try {
                boolean overlayVisible = mOverlayController != null
                        && mOverlayController.isVisible();
                // Skip capture while an LLM request is in flight or the overlay is up:
                // its own pixels would feed the change detector and could self-trigger.
                if (configured && !overlayVisible && mAnalyzing.compareAndSet(false, true)) {
                    Bitmap screenshot = captureScreen();

                    if (screenshot != null) {
                        mConsecutiveCaptureFailures = 0;
                        long now = System.currentTimeMillis();
                        boolean stale = now - mLastEscalationAtMs >= STALE_TASK_REANALYZE_MS;
                        boolean allowForce = stale && mForcedReanalyses < MAX_FORCED_REANALYSES;
                        boolean escalate;
                        if (allowForce) {
                            escalate = mFrameDetector.forceAnalyze(screenshot);
                            if (escalate) {
                                mForcedReanalyses++;
                                Log.i(TAG, "Stale-task watchdog: forcing re-analysis (#"
                                        + mForcedReanalyses + ", no escalation for "
                                        + sinceEscalationSeconds(mLastEscalationAtMs, now) + "s)");
                            }
                        } else {
                            escalate = mFrameDetector.shouldAnalyze(screenshot);
                            if (escalate) {
                                // a natural change refills the watchdog budget
                                mForcedReanalyses = 0;
                            }
                        }
                        if (escalate) {
                            analyzeWithLLM(screenshot);
                        } else {
                            mAnalyzing.set(false);
                        }
                    } else {
                        mAnalyzing.set(false);
                        if (++mConsecutiveCaptureFailures == CAPTURE_FAILURE_LOG_THRESHOLD) {
                            Log.e(TAG, "Screen capture keeps failing ("
                                    + mConsecutiveCaptureFailures + " attempts)");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during screen capture", e);
                mAnalyzing.set(false);
            }

            mCaptureHandler.postDelayed(this, Math.max(intervalMs, 100));
        }
    };

    /**
     * Capture via the WindowManager API, falling back to the mirror
     * VirtualDisplay + ImageReader if the primary path fails.
     */
    private Bitmap captureScreen() {
        // Primary: platform capture API (requires READ_FRAME_BUFFER)
        try {
            Bitmap screenshot = mScreenCaptureService.captureFrame();
            if (screenshot != null) {
                return screenshot;
            }
        } catch (Exception e) {
            Log.w(TAG, "WindowManager capture failed, trying fallback", e);
        }

        // Fallback: mirrored VirtualDisplay, created lazily on first failure
        if (mImageReader == null) {
            setupVirtualDisplay();
        }
        if (mImageReader != null) {
            try {
                Image image = mImageReader.acquireLatestImage();
                if (image != null) {
                    try {
                        return imageToBitmap(image);
                    } finally {
                        image.close();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "ImageReader capture failed", e);
            }
        }
        return null;
    }

    /**
     * Create a mirror VirtualDisplay feeding an ImageReader. Requires
     * CAPTURE_VIDEO_OUTPUT (granted via the privapp-permissions XML).
     */
    private void setupVirtualDisplay() {
        try {
            DisplayManager displayManager = getSystemService(DisplayManager.class);
            Display display = displayManager != null
                    ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display == null) {
                return;
            }
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getMetrics(metrics);

            mImageReader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2);
            mVirtualDisplay = displayManager.createVirtualDisplay(
                    "AIPhoneAssistantMirror", size.x, size.y, metrics.densityDpi,
                    mImageReader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR);
            if (mVirtualDisplay == null) {
                Log.w(TAG, "Failed to create mirror VirtualDisplay");
                mImageReader.close();
                mImageReader = null;
            } else {
                Log.i(TAG, "Mirror VirtualDisplay created as capture fallback");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Not allowed to create mirror display - check CAPTURE_VIDEO_OUTPUT", e);
            mImageReader = null;
        } catch (Exception e) {
            Log.e(TAG, "Error setting up VirtualDisplay", e);
            mImageReader = null;
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(),
                Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return bitmap;
    }

    private void releaseScreenCapture() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    /**
     * Send the frame to the LLM and act on the reply. The mAnalyzing flag is
     * already set by the caller; it is cleared on completion (including
     * errors).
     */
    private static long sinceEscalationSeconds(long lastEscalationAtMs, long now) {
        return Math.max(0, (now - lastEscalationAtMs) / 1000);
    }

    private void analyzeWithLLM(Bitmap screenshot) {
        mLastEscalationAtMs = System.currentTimeMillis();
        if (mOverlayController != null) {
            mOverlayController.showThinking();
        }

        mLLMClientService.analyzeScreen(screenshot,
                mScreenCaptureService.getDisplayWidth(),
                mScreenCaptureService.getDisplayHeight(),
                new LLMClientService.LLMCallback() {
            @Override
            public void onLLMResponse(String response, InputInjectionService.InputAction action) {
                // Inject on the capture (background) thread, never the UI thread:
                // the injectors block with Thread.sleep and would freeze the UI.
                mCaptureHandler.post(() -> {
                    try {
                        if (action != null && mInputInjectionService != null) {
                            android.graphics.PointF point = action.getPrimaryPoint();
                            if (mOverlayController != null && point != null) {
                                mOverlayController.showActing(point.x, point.y);
                            }
                            mInputInjectionService.executeAction(action);
                            mFrameDetector.notifyActionInjected();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error executing action", e);
                    } finally {
                        mAnalyzing.set(false);
                        // Let the acting marker finish, then hide and resume capture.
                        if (mOverlayController != null) {
                            mOverlayController.hideDelayed(ACT_HIDE_DELAY_MS);
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "LLM analysis error: " + error);
                mAnalyzing.set(false);
                if (mOverlayController != null) {
                    mOverlayController.hide();
                }
            }
        });
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();
    }

    /** Binder for local service access. */
    public class AIPhoneAssistantBinder extends Binder {
        public AIPhoneAssistantService getService() {
            return AIPhoneAssistantService.this;
        }
    }
}
