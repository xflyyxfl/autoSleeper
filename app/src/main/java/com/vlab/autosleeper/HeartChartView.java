package com.vlab.autosleeper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * 心率曲线：手环按 10 分钟一格记录，这里把最近若干格画成折线。
 *
 * 空格（-1）处断线，不连成假的下降沿。
 * 同时画出阈值线，方便肉眼判断"多久低于阈值了"。
 */
public class HeartChartView extends View {

    /** 一屏显示多少格，72 格 = 12 小时。 */
    private static final int DEFAULT_VISIBLE = 72;

    private static final int BG = 0xFF12161C;
    private static final int GRID = 0xFF2A313B;
    private static final int AXIS_TEXT = 0xFF8894A6;
    private static final int LINE = 0xFF4DD0E1;
    private static final int DOT = 0xFFB2EBF2;
    private static final int THRESH = 0xFFEF5350;
    private static final int EMPTY_TEXT = 0xFF6B7787;

    private final Paint pBg = new Paint();
    private final Paint pGrid = new Paint();
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pThr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int[] slots = new int[0];
    private int lastIndex = -1;
    private int hour = -1;
    private int minute = -1;
    private int threshold = 55;
    private int visible = DEFAULT_VISIBLE;
    private String footer = "";

    private final float density;

    public HeartChartView(Context c) {
        this(c, null);
    }

    public HeartChartView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;

        pBg.setColor(BG);
        pBg.setStyle(Paint.Style.FILL);

        pGrid.setColor(GRID);
        pGrid.setStrokeWidth(dp(1));
        pGrid.setStyle(Paint.Style.STROKE);

        pText.setColor(AXIS_TEXT);
        pText.setTextSize(sp(9));

        pLine.setColor(LINE);
        pLine.setStrokeWidth(dp(1.8f));
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pLine.setStrokeCap(Paint.Cap.ROUND);

        pDot.setColor(DOT);
        pDot.setStyle(Paint.Style.FILL);

        pThr.setColor(THRESH);
        pThr.setStrokeWidth(dp(1.2f));
        pThr.setStyle(Paint.Style.STROKE);
        pThr.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    /** 喂数据：曲线 + 判定阈值。 */
    public void setData(int[] s, int lastIdx, int h, int m, int thr) {
        this.slots = s == null ? new int[0] : s;
        this.lastIndex = lastIdx;
        this.hour = h;
        this.minute = m;
        this.threshold = thr;
        invalidate();
    }

    public void setFooter(String s) {
        this.footer = s == null ? "" : s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, pBg);

        float left = dp(34);
        float right = w - dp(8);
        float top = dp(10);
        float bottom = h - dp(20);

        if (right <= left || bottom <= top) {
            return;
        }

        // 取最近 visible 格
        int from;
        int count;
        if (lastIndex >= 0) {
            count = Math.min(visible, lastIndex + 1);
            from = lastIndex - count + 1;
        } else {
            from = 0;
            count = Math.min(visible, slots.length);
        }

        int yMin = 40;
        int yMax = 130;

        // 横向网格 + Y 轴刻度
        for (int bpm = yMin; bpm <= yMax; bpm += 30) {
            float y = mapY(bpm, top, bottom, yMin, yMax);
            canvas.drawLine(left, y, right, y, pGrid);
            canvas.drawText(String.valueOf(bpm), dp(4), y + sp(3), pText);
        }

        if (count <= 0) {
            pText.setColor(EMPTY_TEXT);
            pText.setTextSize(sp(11));
            canvas.drawText("还没有曲线数据，先点「立即测一次」", left + dp(8), (top + bottom) / 2, pText);
            pText.setTextSize(sp(9));
            pText.setColor(AXIS_TEXT);
            return;
        }

        float stepX = count <= 1 ? 0 : (right - left) / (count - 1);

        // 阈值线
        if (threshold > yMin && threshold < yMax) {
            float yt = mapY(threshold, top, bottom, yMin, yMax);
            canvas.drawLine(left, yt, right, yt, pThr);
            canvas.drawText(threshold + " 阈值", right - dp(46), yt - dp(3), pText);
        }

        // 折线
        path.reset();
        boolean started = false;
        float lastX = left;
        float lastY = bottom;
        for (int i = 0; i < count; i++) {
            int idx = from + i;
            int v = idx < slots.length ? slots[idx] : BandClient.NO_DATA;
            if (v < 30 || v > 220) {
                started = false;
                continue;
            }
            float x = left + stepX * i;
            float y = mapY(v, top, bottom, yMin, yMax);
            if (started) {
                path.lineTo(x, y);
            } else {
                path.moveTo(x, y);
                started = true;
            }
            lastX = x;
            lastY = y;
        }
        canvas.drawPath(path, pLine);

        // 最新一点画个实心圆
        if (started) {
            canvas.drawCircle(lastX, lastY, dp(2.6f), pDot);
        }

        // 底部时间刻度（4 个）
        pText.setTextSize(sp(8));
        for (int k = 0; k <= 3; k++) {
            float x = left + (right - left) * k / 3f;
            String lab = timeLabel(from + (int) ((count - 1) * k / 3f));
            float tw = pText.measureText(lab);
            float tx = Math.max(left, Math.min(right - tw, x - tw / 2));
            canvas.drawText(lab, tx, h - dp(6), pText);
        }

        if (footer != null && footer.length() > 0) {
            pText.setTextSize(sp(9));
            canvas.drawText(footer, left, top + sp(9), pText);
        }
    }

    /** 格号 → 具体时刻。以手环最后上报的采集时间反推。 */
    private String timeLabel(int idx) {
        if (hour < 0 || minute < 0 || lastIndex < 0) {
            return "";
        }
        int totalMin = hour * 60 + minute - (lastIndex - idx) * BandClient.SLOT_MINUTES;
        totalMin = ((totalMin % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", totalMin / 60, totalMin % 60);
    }

    private static float mapY(int v, float top, float bottom, int yMin, int yMax) {
        float t = (v - yMin) / (float) (yMax - yMin);
        t = Math.max(0f, Math.min(1f, t));
        return bottom - (bottom - top) * t;
    }
}
