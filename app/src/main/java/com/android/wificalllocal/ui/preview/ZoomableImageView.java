package com.android.wificalllocal.ui.preview;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView supporting pinch-to-zoom, double-tap zoom toggle, and single-finger panning,
 * while cooperating cleanly with ViewPager2 horizontal swiping when at 1x scale.
 */
public class ZoomableImageView extends AppCompatImageView {

    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 4.5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];

    private float currentScale = 1.0f;
    private float baseImageWidth = 0f;
    private float baseImageHeight = 0f;

    private final PointF lastTouch = new PointF();
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean isDragging = false;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ZoomableImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float targetScale = currentScale * factor;
                if (targetScale < MIN_SCALE) {
                    factor = MIN_SCALE / currentScale;
                    currentScale = MIN_SCALE;
                } else if (targetScale > MAX_SCALE) {
                    factor = MAX_SCALE / currentScale;
                    currentScale = MAX_SCALE;
                } else {
                    currentScale = targetScale;
                }
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (currentScale > 1.25f) {
                    fitToScreen();
                } else {
                    float target = DOUBLE_TAP_SCALE;
                    float factor = target / currentScale;
                    currentScale = target;
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    fixTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }
        });
    }

    public void resetZoom() {
        fitToScreen();
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitToScreen);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToScreen();
    }

    private void fitToScreen() {
        Drawable d = getDrawable();
        if (d == null) return;
        int vw = getWidth();
        int vh = getHeight();
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return;

        float scale = Math.min((float) vw / dw, (float) vh / dh);
        baseImageWidth = dw * scale;
        baseImageHeight = dh * scale;

        float dx = (vw - baseImageWidth) / 2f;
        float dy = (vh - baseImageHeight) / 2f;

        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        currentScale = 1.0f;
        setImageMatrix(matrix);
    }

    private void fixTranslation() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0 || baseImageWidth <= 0f || baseImageHeight <= 0f) return;

        matrix.getValues(matrixValues);
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];

        float scaledW = baseImageWidth * currentScale;
        float scaledH = baseImageHeight * currentScale;

        float fixX = computeFixDelta(transX, vw, scaledW);
        float fixY = computeFixDelta(transY, vh, scaledH);

        if (fixX != 0f || fixY != 0f) {
            matrix.postTranslate(fixX, fixY);
        }
    }

    private float computeFixDelta(float trans, float viewSize, float contentSize) {
        if (contentSize <= viewSize) {
            float centerOffset = (viewSize - contentSize) / 2f;
            return centerOffset - trans;
        }
        float minTrans = viewSize - contentSize;
        if (trans < minTrans) return minTrans - trans;
        if (trans > 0f) return -trans;
        return 0f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(currentScale > 1.02f || scaleDetector.isInProgress());
        }

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                activePointerId = event.getPointerId(0);
                isDragging = true;
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDragging && !scaleDetector.isInProgress() && currentScale > 1.02f) {
                    int idx = event.findPointerIndex(activePointerId);
                    if (idx >= 0) {
                        float x = event.getX(idx);
                        float y = event.getY(idx);
                        float dx = x - lastTouch.x;
                        float dy = y - lastTouch.y;
                        matrix.postTranslate(dx, dy);
                        fixTranslation();
                        setImageMatrix(matrix);
                        lastTouch.set(x, y);
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    int newIndex = pointerIndex == 0 ? 1 : 0;
                    if (newIndex < event.getPointerCount()) {
                        lastTouch.set(event.getX(newIndex), event.getY(newIndex));
                        activePointerId = event.getPointerId(newIndex);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false;
                if (currentScale <= 1.02f && parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                break;
        }
        return true;
    }
}
