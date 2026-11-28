package com.vlab.autosleeper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 悬浮球：直径 48dp，中间显示最近一次拉到的心率，背景画最近 1 小时的趋势线。
 * 只求一眼能看到数字和大体走向，不承载精确读数。
 */
public class FloatBallView extends View {

    private static final int BG = 0xE0232830;
    private static final int RING = 0xFF4DD0E1;
    private static final int CURVE = 0x664DD0E1;
    private static final int DOT = 0xFFB2EBF2;
    private static final int TEXT = 0xFFFFFFFF;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCurve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();

    private final float density;
    private final float scaled;

    public FloatBallView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        scaled = c.getResources().getDisplayMetrics().scaledDensity;

        pBg.setColor(BG);
        pBg.setStyle(Paint.Style.FILL);

        pRing.setColor(RING);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1));

        pCurve.setColor(CURVE);
        pCurve.setStyle(Paint.Style.STROKE);
        pCurve.setStrokeWidth(dp(0.9f));

        pDot.setColor(DOT);
        pDot.setStyle(Paint.Style.FILL);

        pText.setColor(TEXT);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setFakeBoldText(true);
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return v * scaled;
    }

    public void refresh() {
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(1.5f);

        canvas.drawCircle(cx, cy, r, pBg);
        canvas.drawCircle(cx, cy, r, pRing);

        int[] slots = PulseStore.snapshot();
        int base = PulseStore.cur >= 0 ? PulseStore.cur : PulseStore.lastIndex;

        // 背景趋势线：最近 6 格，只画到圆的中部以上，不盖住数字
        if (base >= 0 && slots.length > 0) {
            int n = 6;
            float top = cy - r * 0.72f;
            float bottom = cy - r * 0.12f;
            line.reset();
            boolean started = false;
            float lastX = 0;
            float lastY = 0;
            for (int k = n - 1; k >= 0; k--) {
                int idx = BandClient.PulseHistory.slotAt(base, k);
                int v = idx < slots.length ? slots[idx] : BandClient.NO_DATA;
                if (v < 30 || v > 220) {
                    started = false;
                    continue;
                }
                float x = cx - r * 0.82f + (2f * r * 0.82f) * (n - 1 - k) / (n - 1);
                float y = mapY(v, top, bottom);
                if (started) {
                    line.lineTo(x, y);
                } else {
                    line.moveTo(x, y);
                    started = true;
                }
                lastX = x;
                lastY = y;
            }
            canvas.drawPath(line, pCurve);
            if (started) {
                canvas.drawCircle(lastX, lastY, dp(1.3f), pDot);
            }
        }

        // 与主界面显示同一个量：判定窗口中位数。
        // 只有还没判定过时才退回当前格的原始值，避免两处数字对不上。
        int bpm = PulseStore.median;
        if (bpm <= 0 && base >= 0 && base < slots.length && slots[base] >= 30) {
            bpm = slots[base];
        }
        pText.setTextSize(sp(bpm >= 100 ? 11 : 12.5f));
        canvas.drawText(bpm > 0 ? String.valueOf(bpm) : "--",
                cx, cy + r * 0.55f, pText);
    }

    private static float mapY(int v, float top, float bottom) {
        float t = (v - 45) / 60f;
        t = Math.max(0f, Math.min(1f, t));
        return bottom - (bottom - top) * t;
    }
}
