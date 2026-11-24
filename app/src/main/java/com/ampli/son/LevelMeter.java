package com.ampli.son;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Deux barres de niveau (G / D) avec la ligne de seuil. */
public class LevelMeter extends View {

    private static final float MIN_DB = -60f;
    private float left = -100f, right = -100f, threshold = -6f;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final RectF rect = new RectF();

    public LevelMeter(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        bg.setColor(0xFFE3E6EA);
        line.setColor(0xFFD32F2F);
        line.setStrokeWidth(3 * density);
        text.setColor(0xFF37474F);
        text.setTextSize(14 * density);
        text.setFakeBoldText(true);
    }

    public void setLevels(float l, float r, float thr) {
        if (Math.abs(l - left) < 0.05f && Math.abs(r - right) < 0.05f && thr == threshold) return;
        left = l;
        right = r;
        threshold = thr;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int height = (int) (76 * density);
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    private float frac(float db) {
        float f = (db - MIN_DB) / (0f - MIN_DB);
        return Math.max(0f, Math.min(1f, f));
    }

    private void drawBar(Canvas c, float top, float db, String label) {
        float labelW = 26 * density;
        float barH = 26 * density;
        float x0 = labelW, x1 = getWidth() - 4 * density;
        c.drawText(label, 4 * density, top + barH * 0.72f, text);
        rect.set(x0, top, x1, top + barH);
        c.drawRoundRect(rect, 6 * density, 6 * density, bg);
        float f = frac(db);
        if (f > 0f) {
            int color;
            if (db >= threshold) color = 0xFFD32F2F;
            else if (db >= threshold - 6f) color = 0xFFF9A825;
            else color = 0xFF2E7D32;
            fill.setColor(color);
            rect.set(x0, top, x0 + (x1 - x0) * f, top + barH);
            c.drawRoundRect(rect, 6 * density, 6 * density, fill);
        }
        float tx = x0 + (x1 - x0) * frac(threshold);
        c.drawLine(tx, top - 3 * density, tx, top + barH + 3 * density, line);
    }

    @Override
    protected void onDraw(Canvas c) {
        drawBar(c, 6 * density, left, "G");
        drawBar(c, 42 * density, right, "D");
    }
}
