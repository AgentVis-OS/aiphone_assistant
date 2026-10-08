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

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Frame Change Detector — cheap, local decision of *when* a screen capture is worth
 * sending to the LLM.
 *
 * Instead of shipping every captured frame to the backend, this detector escalates only
 * when the screen has (a) settled after a transition and (b) changed meaningfully versus
 * the last analyzed state. This keeps the LLM out of the steady-state hot loop.
 *
 * Per-frame pipeline:
 *   1. Downscale to a small grayscale thumbnail (color is discarded; differences are
 *      structural).
 *   2. Compare to the previous frame. Frames under a noise floor count as "stable".
 *      The screen is only considered "settled" after several consecutive stable frames,
 *      which skips mid-animation / mid-scroll transients.
 *   3. Once settled, compare to the last analyzed frame. Only escalate when the
 *      difference exceeds a significance threshold (a real UI change, not a blinking
 *      cursor or clock tick).
 *   4. Two cooldowns: a minimum interval between LLM calls, and a post-action cooldown
 *      entered via {@link #notifyActionInjected()} so the UI transition caused by an
 *      injected action is allowed to settle before re-analysis (avoids feedback thrash).
 *
 * Not thread-safe: intended to be driven from a single capture thread.
 */
public class FrameChangeDetector {
    private static final String TAG = "AIPhoneAssistant.Detector";
    private static final boolean DEBUG = true;

    // Thumbnail dimensions — small enough that the diff is trivial to compute.
    private static final int THUMB_WIDTH = 32;
    private static final int THUMB_HEIGHT = 64;

    // Mean-absolute-difference thresholds, expressed as a percentage of the 0..255 range.
    private static final double STABLE_NOISE_FLOOR = 0.8;   // vs previous frame => "stable"
    private static final double SIGNIFICANT_CHANGE = 3.0;   // vs last analyzed => escalate
    // 5 consecutive stable frames (~2.5s at the 500ms interval): system window
    // animations run 0.4-1s, and 3 frames was short enough that escalations
    // fired on still-transitioning frames (the injected action then landed on
    // a screen that was mid-animation and did nothing).
    private static final int STABLE_FRAMES_REQUIRED = 5;    // consecutive stable frames => "settled"

    // Cooldowns.
    private static final long MIN_ANALYSIS_INTERVAL_MS = 1500;  // min gap between LLM calls
    private static final long POST_ACTION_COOLDOWN_MS = 1200;   // suppress right after an action

    private int[] mPrevThumb;
    private int[] mLastAnalyzedThumb;
    private int mConsecutiveStable = 0;
    private long mLastAnalysisTimeMs = 0;
    private long mLastActionTimeMs = Long.MIN_VALUE / 2;  // effectively "-infinity"
    private boolean mBootstrapped = false;

    /**
     * Decide whether the given frame should be analyzed by the LLM.
     * When this returns true the frame's thumbnail is recorded as the new analysis
     * baseline, so callers do not need a separate callback.
     *
     * @return true if the frame represents a meaningful, settled change worth analyzing
     */
    public boolean shouldAnalyze(Bitmap frame) {
        if (frame == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        // Post-action cooldown: let the UI transition settle first.
        if (now - mLastActionTimeMs < POST_ACTION_COOLDOWN_MS) {
            return false;
        }

        int[] thumb = toThumbnail(frame);

        // Bootstrap: analyze the first usable frame so we have a baseline.
        if (!mBootstrapped) {
            mBootstrapped = true;
            mPrevThumb = thumb;
            mLastAnalyzedThumb = thumb;
            mConsecutiveStable = STABLE_FRAMES_REQUIRED;
            mLastAnalysisTimeMs = now;
            return true;
        }

        double diffVsPrev = meanAbsDiffPercent(thumb, mPrevThumb);
        if (diffVsPrev < STABLE_NOISE_FLOOR) {
            mConsecutiveStable++;
        } else {
            mConsecutiveStable = 0;
        }
        mPrevThumb = thumb;

        // Require the screen to have settled — never analyze a mid-transition frame.
        if (mConsecutiveStable < STABLE_FRAMES_REQUIRED) {
            return false;
        }

        // Bound the rate of LLM calls.
        if (now - mLastAnalysisTimeMs < MIN_ANALYSIS_INTERVAL_MS) {
            return false;
        }

        // Escalate only on a meaningful change versus the last analyzed state.
        double diffVsBaseline = meanAbsDiffPercent(thumb, mLastAnalyzedThumb);
        if (diffVsBaseline > SIGNIFICANT_CHANGE) {
            mLastAnalyzedThumb = thumb;
            mLastAnalysisTimeMs = now;
            if (DEBUG) {
                Log.d(TAG, "Escalating to LLM; change=" + String.format("%.2f", diffVsBaseline) + "%");
            }
            return true;
        }
        return false;
    }

    /**
     * Notify the detector that an action was just injected. Enters the post-action
     * cooldown and forces the screen to re-settle before the next escalation.
     */
    public void notifyActionInjected() {
        mLastActionTimeMs = System.currentTimeMillis();
        mConsecutiveStable = 0;
    }

    /**
     * Force an analysis of the given frame, bypassing the settle/change gating,
     * and reset the analysis baseline to it. Used by the stale-task watchdog in
     * {@link AIPhoneAssistantService}: when the model steers the screen back to
     * an already-analyzed state, the normal gating would never fire again and
     * the task would stall forever.
     */
    public boolean forceAnalyze(Bitmap frame) {
        if (frame == null) {
            return false;
        }
        int[] thumb = toThumbnail(frame);
        mPrevThumb = thumb;
        mLastAnalyzedThumb = thumb;
        mConsecutiveStable = STABLE_FRAMES_REQUIRED;
        mLastAnalysisTimeMs = System.currentTimeMillis();
        if (DEBUG) {
            Log.d(TAG, "Forcing analysis (stale-task watchdog)");
        }
        return true;
    }

    /**
     * Downscale the frame to a small grayscale thumbnail. Returns an int[] of luminance
     * values (0..255). The source bitmap is never recycled here.
     */
    private int[] toThumbnail(Bitmap frame) {
        Bitmap scaled = Bitmap.createScaledBitmap(frame, THUMB_WIDTH, THUMB_HEIGHT, true);
        int[] rgba = new int[THUMB_WIDTH * THUMB_HEIGHT];
        scaled.getPixels(rgba, 0, THUMB_WIDTH, 0, 0, THUMB_WIDTH, THUMB_HEIGHT);

        int[] gray = new int[rgba.length];
        for (int i = 0; i < rgba.length; i++) {
            int c = rgba[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            gray[i] = (r * 30 + g * 59 + b * 11) / 100;  // perceptual luminance
        }

        if (scaled != frame) {
            scaled.recycle();
        }
        return gray;
    }

    /**
     * Mean absolute difference between two grayscale thumbnails, as a percentage of 255.
     * Returns a large value (100%) when the inputs cannot be compared, which safely
     * avoids treating an incomparable state as "unchanged".
     */
    private static double meanAbsDiffPercent(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 100.0;
        }
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return (sum / (double) a.length) / 255.0 * 100.0;
    }
}
