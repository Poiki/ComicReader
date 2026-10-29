package org.comicreader.app;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;

final class PageView extends View {
    interface Actions { void turn(int direction); void advance(int direction); void toggleControls(); }
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaling;
    private final Actions actions;
    private Bitmap bitmap;
    private boolean fitWidth;
    private float zoom = 1, x, y;

    PageView(Context context, Actions actions) {
        super(context); this.actions = actions; setBackgroundColor(Color.BLACK);
        setFocusable(true); setClickable(true); setContentDescription("Página del cómic");
        scaling = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float previous = zoom; zoom = Math.max(1, Math.min(5, zoom * detector.getScaleFactor()));
                float ratio = zoom / previous;
                x = detector.getFocusX() - (detector.getFocusX() - x) * ratio;
                y = detector.getFocusY() - (detector.getFocusY() - y) * ratio;
                constrain(); invalidate(); return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (event.getX() < getWidth() / 3f) actions.turn(-1);
                else if (event.getX() > getWidth() * 2 / 3f) actions.turn(1);
                else performClick();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                float old = zoom; zoom = zoom > 1 ? 1 : 2.5f;
                x = event.getX() - (event.getX() - x) * zoom / old;
                y = event.getY() - (event.getY() - y) * zoom / old;
                constrain(); invalidate(); return true;
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (!scaling.isInProgress()) { x -= dx; y -= dy; constrain(); invalidate(); }
                return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent current, float vx, float vy) {
                if (zoom == 1 && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 600
                        && Math.abs(current.getX() - first.getX()) > getWidth() / 6f) {
                    actions.turn(vx < 0 ? 1 : -1); return true;
                }
                return false;
            }
        });
    }
    void show(Bitmap bitmap, boolean width, String description) {
        this.bitmap = bitmap; fitWidth = width; setContentDescription(description); reset();
    }
    void clear() { bitmap = null; invalidate(); }
    private float scale() {
        if (bitmap == null) return 1;
        float width = (float) getWidth() / bitmap.getWidth();
        return (fitWidth ? width : Math.min(width, (float) getHeight() / bitmap.getHeight())) * zoom;
    }
    private void reset() {
        zoom = 1;
        if (bitmap != null) {
            float scale = scale(); x = (getWidth() - bitmap.getWidth() * scale) / 2;
            y = fitWidth ? 0 : (getHeight() - bitmap.getHeight() * scale) / 2;
        }
        constrain(); invalidate();
    }
    private void constrain() {
        if (bitmap == null) return;
        float width = bitmap.getWidth() * scale(), height = bitmap.getHeight() * scale();
        x = width <= getWidth() ? (getWidth() - width) / 2 : Math.max(getWidth() - width, Math.min(0, x));
        y = height <= getHeight() ? (getHeight() - height) / 2 : Math.max(getHeight() - height, Math.min(0, y));
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { reset(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            float scale = scale(); destination.set(x, y, x + bitmap.getWidth() * scale, y + bitmap.getHeight() * scale);
            canvas.drawBitmap(bitmap, null, destination, paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaling.onTouchEvent(event); gestures.onTouchEvent(event); return true;
    }
    @Override public boolean performClick() { super.performClick(); actions.toggleControls(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { actions.advance(1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { actions.advance(-1); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
}
