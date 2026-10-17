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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Semi-transparent pill floating over the middle of the status bar while a
 * task is executing. It is the user's interruption affordance: tapping it
 * three times in quick succession (same spot) pauses the task and opens the
 * stop-confirmation dialog.
 *
 * The left end of the pill carries a small status glyph reflecting the
 * agent's current phase (see {@link #setPhase(int)}): an eye while reading
 * the screen, bouncing dots while the LLM is thinking, an expanding ring
 * while input is being injected.
 *
 * The window it lives in sits ABOVE the status bar
 * (TYPE_STATUS_BAR_ADDITIONAL, requires the INTERNAL_SYSTEM_WINDOW permission
 * held via the platform signature), otherwise the status bar would swallow
 * every touch. While the assistant itself injects input the service flips the
 * window to FLAG_NOT_TOUCHABLE so model taps near the top of the screen pass
 * through unaffected.
 */
public class InterruptBarView extends View {
    private static final String TAG = "AIPhoneAssistant.Bar";

    /** Consecutive taps within this gap count towards the interrupt gesture. */
    private static final long TAP_GAP_MS = 600;
    /** Taps must land within this distance (px) of each other. */
    private static final float TAP_SLOP_DP = 48f;
    /** Taps needed to trigger the pause + confirm dialog. */
    private static final int TAPS_REQUIRED = 3;

    /** Callbacks invoked on the UI thread. */
    public interface Listener {
        /** Very first tap of a sequence — show the "tap N more times" hint. */
        void onInterruptGestureStarted();
        /** Taps remaining after the current one, 0 when the gesture fires. */
        void onInterruptGestureProgress(int tapsRemaining);
        /** The interrupt gesture completed: pause and confirm. */
        void onInterruptRequested();
    }

    // Agent phases shown by the glyph at the pill's left end.
    /** Reading the screen (capture loop between analyses). */
    public static final int PHASE_OBSERVING = 0;
    /** LLM request in flight. */
    public static final int PHASE_THINKING = 1;
    /** Injecting the model's action. */
    public static final int PHASE_ACTING = 2;

    private final Listener mListener;
    private final float mTapSlopPx;

    private final Paint mPillPaint;
    private final Paint mIconPaint;
    private final Paint mPhaseStrokePaint;
    private final Paint mPhaseFillPaint;

    private int mTapCount;
    private long mLastTapAt;
    private float mLastTapX;
    private float mLastTapY;

    private ValueAnimator mPulse;
    private float mPulseAlpha; // 0..1 transient highlight after each tap

    private int mPhase = PHASE_OBSERVING;
    private ValueAnimator mPhaseAnim;
    private float mPhaseT; // 0..1 loop position driving the phase glyph

    public InterruptBarView(Context context, Listener listener) {
        super(context);
        mListener = listener;
        mTapSlopPx = TAP_SLOP_DP * context.getResources().getDisplayMetrics().density;

        mPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPillPaint.setStyle(Paint.Style.FILL);

        mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mIconPaint.setStyle(Paint.Style.FILL);
        mIconPaint.setColor(0xCCFFFFFF);

        mPhaseStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPhaseStrokePaint.setStyle(Paint.Style.STROKE);

        mPhaseFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPhaseFillPaint.setStyle(Paint.Style.FILL);

        mPulse = ValueAnimator.ofFloat(1f, 0f);
        mPulse.setDuration(450);
        mPulse.addUpdateListener(animation -> {
            mPulseAlpha = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Pill: full view size minus a small vertical margin so it doesn't
        // collide with any screen corner rounding / cutout.
        float inset = h * 0.12f;
        RectF pill = new RectF(0, inset, w, h - inset);

        int baseAlpha = 115; // ~45% — visible but unobtrusive over the status bar
        mPillPaint.setColor(0xFF000000);
        mPillPaint.setAlpha(Math.min(255, baseAlpha + (int) (mPulseAlpha * 100)));
        canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, mPillPaint);

        drawPhaseIcon(canvas, pill);

        // Pause icon: two rounded vertical bars centered in the pill.
        float iconH = pill.height() * 0.42f;
        float barW = Math.max(3f, h * 0.06f);
        float gap = barW * 1.6f;
        float cx = w / 2f;
        float cy = pill.centerY();
        mIconPaint.setAlpha(Math.min(255, 204 + (int) (mPulseAlpha * 50)));
        RectF left = new RectF(cx - gap - barW, cy - iconH / 2f, cx - gap, cy + iconH / 2f);
        RectF right = new RectF(cx + gap, cy - iconH / 2f, cx + gap + barW, cy + iconH / 2f);
        canvas.drawRoundRect(left, barW / 2f, barW / 2f, mIconPaint);
        canvas.drawRoundRect(right, barW / 2f, barW / 2f, mIconPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            return true;
        }

        long now = System.currentTimeMillis();
        float x = event.getX();
        float y = event.getY();

        boolean inSequence = (now - mLastTapAt) <= TAP_GAP_MS
                && Math.hypot(x - mLastTapX, y - mLastTapY) <= mTapSlopPx;
        mTapCount = inSequence ? mTapCount + 1 : 1;
        mLastTapAt = now;
        mLastTapX = x;
        mLastTapY = y;

        restartPulse();

        if (mTapCount == 1 && mListener != null) {
            mListener.onInterruptGestureStarted();
        }
        if (mTapCount >= TAPS_REQUIRED) {
            mTapCount = 0;
            if (mListener != null) {
                mListener.onInterruptRequested();
            }
        } else if (mListener != null) {
            mListener.onInterruptGestureProgress(TAPS_REQUIRED - mTapCount);
        }
        return true;
    }

    /** Reset the tap sequence (e.g. when the bar is shown for a new task). */
    public void resetTapSequence() {
        mTapCount = 0;
        mLastTapAt = 0;
    }

    /**
     * Switch the status glyph at the pill's left end to the given agent
     * phase (one of the {@code PHASE_*} constants). Safe to call with the
     * current phase; the change is picked up on the next frame.
     */
    public void setPhase(int phase) {
        if (phase == mPhase) {
            return;
        }
        mPhase = phase;
        invalidate();
    }

    /**
     * Small status glyph at the pill's left end, one distinct color per
     * phase: a breathing eye (observing, blue), three bouncing dots
     * (thinking, amber), a dot with an expanding ripple (acting, green).
     */
    private void drawPhaseIcon(Canvas canvas, RectF pill) {
        float r = pill.height() * 0.22f;
        float ix = pill.left + pill.height() * 0.6f;
        float iy = pill.centerY();

        int color;
        switch (mPhase) {
            case PHASE_THINKING:
                color = 0xFFFFD54F; // amber
                break;
            case PHASE_ACTING:
                color = 0xFF81C784; // green
                break;
            default:
                color = 0xFF8AB4F8; // blue
                break;
        }
        mPhaseStrokePaint.setColor(color);
        mPhaseStrokePaint.setStrokeWidth(Math.max(2f, r * 0.24f));
        mPhaseFillPaint.setColor(color);

        if (mPhase == PHASE_THINKING) {
            for (int i = 0; i < 3; i++) {
                float wave = waveAt(mPhaseT - i * 0.15f);
                mPhaseFillPaint.setAlpha((int) (110 + 145 * wave));
                canvas.drawCircle(ix + (i - 1) * r * 1.4f,
                        iy - r * (wave - 0.5f) * 0.7f, r * 0.4f, mPhaseFillPaint);
            }
        } else if (mPhase == PHASE_ACTING) {
            mPhaseFillPaint.setAlpha(235);
            canvas.drawCircle(ix, iy, r * 0.34f, mPhaseFillPaint);
            float frac = mPhaseT - (float) Math.floor(mPhaseT);
            mPhaseStrokePaint.setAlpha((int) (200 * (1f - frac)));
            canvas.drawCircle(ix, iy, r * (0.5f + 0.8f * frac), mPhaseStrokePaint);
        } else {
            RectF eye = new RectF(ix - r * 1.7f, iy - r * 0.95f,
                    ix + r * 1.7f, iy + r * 0.95f);
            mPhaseStrokePaint.setAlpha(210);
            canvas.drawOval(eye, mPhaseStrokePaint);
            float breathe = 0.72f + 0.28f * waveAt(mPhaseT);
            mPhaseFillPaint.setAlpha(235);
            canvas.drawCircle(ix, iy, r * 0.45f * breathe, mPhaseFillPaint);
        }
    }

    /** Smooth 0..1..0 wave over one loop; {@code t} may be any float. */
    private static float waveAt(float t) {
        float frac = t - (float) Math.floor(t);
        return (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * frac));
    }

    private void startPhaseAnim() {
        if (mPhaseAnim == null) {
            mPhaseAnim = ValueAnimator.ofFloat(0f, 1f);
            mPhaseAnim.setDuration(1400);
            mPhaseAnim.setRepeatCount(ValueAnimator.INFINITE);
            mPhaseAnim.setInterpolator(new LinearInterpolator());
            mPhaseAnim.addUpdateListener(animation -> {
                mPhaseT = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        if (!mPhaseAnim.isRunning()) {
            mPhaseAnim.start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startPhaseAnim();
    }

    private void restartPulse() {
        if (mPulse.isRunning()) {
            mPulse.cancel();
        }
        mPulse.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mPulse != null && mPulse.isRunning()) {
            mPulse.cancel();
        }
        if (mPhaseAnim != null && mPhaseAnim.isRunning()) {
            mPhaseAnim.cancel();
        }
        super.onDetachedFromWindow();
    }
}
