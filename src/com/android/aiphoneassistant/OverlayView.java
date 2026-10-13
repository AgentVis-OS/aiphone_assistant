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
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Overlay view that visually signals when the assistant is active.
 *
 * Two modes, drawn via Canvas (no external dependencies):
 *   - THINKING: a subtle full-screen pulsing tint plus a scanning beam, shown while the
 *     LLM request is in flight.
 *   - ACTING:   an expanding ripple ring and center dot drawn at the injected coordinates,
 *     shown at the moment and location of input injection.
 *
 * The background is fully transparent, so only the effect is visible. Designed so a
 * GLSurfaceView / GLSL shader variant could replace this class behind the same
 * show/hide contract used by {@link OverlayController}.
 */
public class OverlayView extends View {
    public static final int MODE_HIDDEN = 0;
    public static final int MODE_THINKING = 1;
    public static final int MODE_ACTING = 2;

    private static final long ACTING_DURATION_MS = 450;
    private static final int ACCENT = Color.argb(255, 0x33, 0xCC, 0xFF);

    private volatile int mMode = MODE_HIDDEN;
    private float mActionX;
    private float mActionY;
    private long mActionStartMs;

    private final Paint mTintPaint;
    private final Paint mBeamPaint;
    private final Paint mRipplePaint;

    private ValueAnimator mPulse;
    private float mPulseValue;  // 0..1, drives the animation

    public OverlayView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRipplePaint.setStyle(Paint.Style.STROKE);
        mRipplePaint.setStrokeWidth(12f);
        mRipplePaint.setColor(ACCENT);

        mPulse = ValueAnimator.ofFloat(0f, 1f);
        mPulse.setDuration(1400);
        mPulse.setRepeatCount(ValueAnimator.INFINITE);
        mPulse.setRepeatMode(ValueAnimator.RESTART);
        mPulse.addUpdateListener(animation -> {
            mPulseValue = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    /** Show the "thinking" effect. */
    public void showThinking() {
        mMode = MODE_THINKING;
        startPulse();
        invalidate();
    }

    /** Show the "acting" ripple at the given screen coordinates. */
    public void showActing(float x, float y) {
        mMode = MODE_ACTING;
        mActionX = x;
        mActionY = y;
        mActionStartMs = SystemClock.uptimeMillis();
        startPulse();
        invalidate();
    }

    /** Clear all effects. */
    public void hide() {
        mMode = MODE_HIDDEN;
        if (mPulse != null && mPulse.isStarted()) {
            mPulse.cancel();
        }
        invalidate();
    }

    private void startPulse() {
        if (mPulse != null && !mPulse.isStarted()) {
            mPulse.start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        if (mMode == MODE_THINKING) {
            drawThinking(canvas, w, h);
        } else if (mMode == MODE_ACTING) {
            drawActing(canvas, w, h);
        }
    }

    private void drawThinking(Canvas canvas, int w, int h) {
        // Pulsing full-screen tint (subtle alpha band).
        float pulse = 0.5f + 0.5f * (float) Math.sin(mPulseValue * 2f * Math.PI);
        int alpha = (int) (18 + 22 * pulse);  // 18..40
        mTintPaint.setColor(ACCENT);
        mTintPaint.setAlpha(alpha);
        canvas.drawRect(0, 0, w, h, mTintPaint);

        // Scanning beam sweeping top to bottom.
        float beamY = mPulseValue * h;
        mBeamPaint.setShader(new LinearGradient(
                0, beamY - 80, 0, beamY + 80,
                new int[]{Color.TRANSPARENT, Color.argb(90, 0x33, 0xCC, 0xFF), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, beamY - 80, w, beamY + 80, mBeamPaint);
    }

    private void drawActing(Canvas canvas, int w, int h) {
        long elapsed = SystemClock.uptimeMillis() - mActionStartMs;
        float progress = Math.min(1f, elapsed / (float) ACTING_DURATION_MS);

        float maxRadius = Math.min(w, h) * 0.25f;
        float radius = 20f + progress * maxRadius;
        int alpha = Math.max(0, (int) ((1f - progress) * 230));

        // Expanding ripple ring (fades + thins out).
        mRipplePaint.setStyle(Paint.Style.STROKE);
        mRipplePaint.setAlpha(alpha);
        mRipplePaint.setStrokeWidth(14f * (1f - progress) + 2f);
        canvas.drawCircle(mActionX, mActionY, radius, mRipplePaint);

        // Center dot that shrinks with the ripple.
        mRipplePaint.setStyle(Paint.Style.FILL);
        mRipplePaint.setAlpha(alpha);
        canvas.drawCircle(mActionX, mActionY, 16f * (1f - progress) + 6f, mRipplePaint);
        mRipplePaint.setStyle(Paint.Style.STROKE);
    }
}
