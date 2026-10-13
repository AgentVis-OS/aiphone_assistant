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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Full-screen screenshot viewer: fit-center by default, pinch to zoom,
 * drag to pan, double-tap toggles between fit and 2.5x at the tapped point.
 * A confirmed single tap fires {@link #performClick()} (used to close).
 */
public class ZoomableImageView extends ImageView {
    private static final float MAX_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix mMatrix = new Matrix();
    private final float[] mValues = new float[9];
    private final RectF mRect = new RectF();

    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mGestureDetector;

    private float mScale = 1f;
    private boolean mLaidOut;

    private int mActivePointerId = -1;
    private float mLastX;
    private float mLastY;

    public ZoomableImageView(Context context) {
        super(context);
        init();
    }

    /** Constructor used when inflating from XML. */
    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);

        mScaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });

        mGestureDetector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mScale > 1.05f) {
                    zoom(1f / mScale, getWidth() / 2f, getHeight() / 2f);
                } else {
                    zoom(DOUBLE_TAP_SCALE / mScale, e.getX(), e.getY());
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                return true;
            }
        });
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        mLaidOut = false; // re-fit once the new size is known
        requestLayout();
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mLaidOut && getDrawable() != null && getWidth() > 0 && getHeight() > 0) {
            fitToScreen();
        }
    }

    private void fitToScreen() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float iw = d.getIntrinsicWidth();
        float ih = d.getIntrinsicHeight();
        float scale = Math.min(getWidth() / iw, getHeight() / ih);
        mMatrix.setScale(scale, scale);
        mMatrix.postTranslate((getWidth() - iw * scale) / 2f,
                (getHeight() - ih * scale) / 2f);
        mScale = 1f;
        applyMatrix();
        mLaidOut = true;
    }

    private void zoom(float factor, float focusX, float focusY) {
        float newScale = Math.min(Math.max(mScale * factor, 1f), MAX_SCALE);
        factor = newScale / mScale;
        if (factor == 1f) {
            return;
        }
        mMatrix.postScale(factor, factor, focusX, focusY);
        mScale = newScale;
        clampPan();
        applyMatrix();
    }

    /** Keep the image over the view: centered when smaller, edges clamped when larger. */
    private void clampPan() {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        mMatrix.getValues(mValues);
        mRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        mMatrix.mapRect(mRect);

        float dx = 0, dy = 0;
        if (mRect.width() <= getWidth()) {
            dx = (getWidth() - mRect.width()) / 2f - mRect.left;
        } else if (mRect.left > 0) {
            dx = -mRect.left;
        } else if (mRect.right < getWidth()) {
            dx = getWidth() - mRect.right;
        }
        if (mRect.height() <= getHeight()) {
            dy = (getHeight() - mRect.height()) / 2f - mRect.top;
        } else if (mRect.top > 0) {
            dy = -mRect.top;
        } else if (mRect.bottom < getHeight()) {
            dy = getHeight() - mRect.bottom;
        }
        if (dx != 0 || dy != 0) {
            mMatrix.postTranslate(dx, dy);
        }
    }

    private void applyMatrix() {
        setImageMatrix(mMatrix);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);

        if (!mScaleDetector.isInProgress()) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mActivePointerId = event.getPointerId(0);
                    mLastX = event.getX();
                    mLastY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (event.findPointerIndex(mActivePointerId) >= 0 && mScale > 1f) {
                        float dx = event.getX() - mLastX;
                        float dy = event.getY() - mLastY;
                        mMatrix.postTranslate(dx, dy);
                        clampPan();
                        applyMatrix();
                    }
                    mLastX = event.getX();
                    mLastY = event.getY();
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    int idx = event.getActionIndex();
                    if (event.getPointerId(idx) == mActivePointerId) {
                        mActivePointerId = -1;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mActivePointerId = -1;
                    break;
            }
        }
        return true;
    }
}
