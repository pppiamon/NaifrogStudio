package com.naifrog.studio;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

public class EditCanvas extends View {
    private Bitmap source, mask, base;
    private final Paint paint = new Paint(3);
    private final PorterDuffColorFilter overlay = new PorterDuffColorFilter(Color.rgb(174, 218, 74), PorterDuff.Mode.SRC_IN);
    private final RectF destination = new RectF();
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private float startX, startY;
    private float scale = 1;
    public int mode = 0; // 0: 查看，1: 涂抹，2: 擦除，3: 框选
    public float brushDp = 24;
    public boolean overlayVisible = true;
    public Runnable onEdited;
    private record Stroke(Path path, float width, boolean erase, boolean filled) {}

    public EditCanvas(Context context) { super(context); setLayerType(LAYER_TYPE_SOFTWARE, null); setContentDescription("照片编辑画布，绿色区域为替换范围"); }
    public void setImage(Bitmap bitmap, Bitmap selection) {
        source = bitmap;
        mask = selection == null ? Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888)
                : selection.isMutable() ? selection : selection.copy(Bitmap.Config.ARGB_8888, true);
        base = mask.copy(Bitmap.Config.ARGB_8888, true);
        strokes.clear(); invalidate();
    }
    public Bitmap getMask() { return mask; }
    public void selectFace(Rect box) {
        if (source == null) return;
        mask = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mask);
        Paint p = new Paint(3); p.setColor(Color.WHITE);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, box.width() * .012f), BlurMaskFilter.Blur.NORMAL));
        c.drawOval(new RectF(box.left + box.width() * .06f, box.top + box.height() * .14f,
                box.right - box.width() * .06f, box.bottom - box.height() * .02f), p);
        base = mask.copy(Bitmap.Config.ARGB_8888, true); strokes.clear(); changed();
    }
    public void undo() { if (!strokes.isEmpty()) { strokes.remove(strokes.size() - 1); redrawMask(); } }
    public void clearSelection() {
        if (mask == null) return;
        mask.eraseColor(Color.TRANSPARENT); base.eraseColor(Color.TRANSPARENT); strokes.clear(); changed();
    }
    private void changed() { invalidate(); if (onEdited != null) onEdited.run(); }
    private void redrawMask() {
        mask.eraseColor(Color.TRANSPARENT); Canvas c = new Canvas(mask); c.drawBitmap(base, 0, 0, null);
        for (Stroke stroke : strokes) {
            Paint p = new Paint(3); p.setColor(Color.WHITE); p.setStyle(stroke.filled ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setStrokeWidth(stroke.width);
            if (stroke.erase) p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawPath(stroke.path, p);
        }
        changed();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawColor(Color.rgb(236, 239, 228));
        if (source == null) return;
        scale = Math.min(getWidth() / (float)source.getWidth(), getHeight() / (float)source.getHeight());
        float w = source.getWidth() * scale, h = source.getHeight() * scale;
        destination.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        paint.setColorFilter(null); paint.setAlpha(255); c.drawBitmap(source, null, destination, paint);
        if (mask != null && overlayVisible) {
            paint.setColorFilter(overlay);
            paint.setAlpha(112); c.drawBitmap(mask, null, destination, paint); paint.setColorFilter(null); paint.setAlpha(255);
        }
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if (source == null || mask == null || mode == 0) return super.onTouchEvent(event);
        float x = Math.max(0, Math.min(source.getWidth() - 1, (event.getX() - destination.left) / scale));
        float y = Math.max(0, Math.min(source.getHeight() - 1, (event.getY() - destination.top) / scale));
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (!destination.contains(event.getX(), event.getY())) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            startX = x; startY = y;
            Path path = new Path();
            if (mode != 3) { path.moveTo(x, y); path.lineTo(x + .1f, y); }
            current = new Stroke(path, brushDp * getResources().getDisplayMetrics().density / scale, mode == 2, mode == 3);
            strokes.add(current); redrawMask(); return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && current != null) {
            if (current.filled) { updateBox(x, y); return true; }
            for (int i = 0; i < event.getHistorySize(); i++) current.path.lineTo(
                    (event.getHistoricalX(i) - destination.left) / scale, (event.getHistoricalY(i) - destination.top) / scale);
            current.path.lineTo(x, y); redrawMask(); return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (current != null) {
                if (event.getAction() == MotionEvent.ACTION_CANCEL) { strokes.remove(current); redrawMask(); }
                else if (current.filled) updateBox(x, y);
            }
            current = null; getParent().requestDisallowInterceptTouchEvent(false); performClick(); return true;
        }
        return true;
    }
    private void updateBox(float x, float y) {
        current.path.reset();
        current.path.addRect(Math.min(startX, x), Math.min(startY, y), Math.max(startX, x), Math.max(startY, y), Path.Direction.CW);
        redrawMask();
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
