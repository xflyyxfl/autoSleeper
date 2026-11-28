package com.vlab.autosleeper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 悬浮球：圆内画最近 1 小时（6 格）的曲线，中间显示最近一次拉到的心率。
 *
 * 数据直接读 PulseStore，GuardService 每轮拉完曲线会 invalidate() 一次。
 */
public class FloatBallView extends View {

    private static final int BG = 0xE0232830;
    private static final int RING = 0xFF4DD0E1;
    private static final int CURVE = 0x804DD0E1;
    private static final int DOT = 0xFFB2EBF2;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int SUB = 0xFF9AA8B8;
    private static final int SLEEP_TEXT = 0xFFFFB300;

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCurve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path line = new Path();

    private final float density;

    public FloatBallView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;

        pBg.setColor(BG);
        pBg.setStyle(Paint.Style.FILL);

        pRing.setColor(RING);
        pRing.setStyle(Paint.Style.STROKE);
        pRing.setStrokeWidth(dp(1.5f));

        pCurve.setColor(CURVE);
        pCurve.setStyle(Paint.Style.STROKE);
        pCurve.setStrokeWidth(dp(1.2f));

        pDot.setColor(DOT);
        pDot.setStyle(Paint.Style.FILL);

        pText.setColor(TEXT);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setTextSize(sp(15));
        pText.setFakeBoldText(true);

        pSub.setColor(SUB);
        pSub.setTextAlign(Paint.Align.CENTER);
        pSub.setTextSize(sp(7.5f));
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
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
        float r = Math.min(w, h) / 2f - dp(2);

        canvas.drawCircle(cx, cy, r, pBg);
        canvas.drawCircle(cx, cy, r, pRing);

        // 圆内画最近 1 小时的曲线（6 格 × 10 分钟）
        int[] slots = PulseStore.snapshot();
        int base = PulseStore.cur >= 0 ? PulseStore.cur : PulseStore.lastIndex;
        if (base >= 0 && slots.length > 0) {
            clip.reset();
            clip.addCircle(cx, cy, r - dp(1), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);

            int n = 6;
            float top = cy - r * 0.55f;
            float bottom = cy + r * 0.75f;
            line.reset();
            boolean started = false;
            int lastV = -1;
            for (int k = n - 1; k >= 0; k--) {
                int idx = BandClient.PulseHistory.slotAt(base, k);
                int v = idx < slots.length ? slots[idx] : BandClient.NO_DATA;
                if (v < 30 || v > 220) {
                    started = false;
                    continue;
                }
                float x = cx - r * 0.9f + (2f * r * 0.9f) * (n - 1 - k) / (n - 1);
                float y = mapY(v, top, bottom);
                if (started) {
                    line.lineTo(x, y);
                } else {
                    line.moveTo(x, y);
                    started = true;
                }
                lastV = v;
            }
            canvas.drawPath(line, pCurve);
            if (lastV > 0) {
                float ly = mapY(lastV, top, bottom);
                canvas.drawCircle(cx + r * 0.9f, ly, dp(2), pDot);
            }
            canvas.restore();
        }

        // 数字：最近一次拉到的那一格
        int bpm = -1;
        if (base >= 0 && base < slots.length && slots[base] >= 30) {
            bpm = slots[base];
        } else if (PulseStore.median > 0) {
            bpm = PulseStore.median;
        }
        pText.setTextSize(sp(bpm >= 100 ? 15 : 17));
        canvas.drawText(bpm > 0 ? String.valueOf(bpm) : "--", cx, cy + sp(5), pText);

        pSub.setColor(PulseStore.asleep ? SLEEP_TEXT : SUB);
        canvas.drawText(PulseStore.asleep ? "已判睡" : "清醒", cx, cy - r * 0.45f, pSub);
    }

    private static float mapY(int v, float top, float bottom) {
        float t = (v - 45) / 60f;
        t = Math.max(0f, Math.min(1f, t));
        return bottom - (bottom - top) * t;
    }
}
