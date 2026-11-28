package com.vlab.autosleeper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.vlab.autosleeper.core.PulseTable;
import com.vlab.autosleeper.core.SlotMath;

/**
 * 心率曲线：手环按 10 分钟一格记录，这里把最近若干格画成折线。
 *
 * 数据源改成 {@link PulseTable}：窗口、环形回绕、有效值判断都由 core 层统一提供，
 * 画图不再自己算格号，也不会出现「曲线和判定用的不是同一份数据」。
 *
 * 空格处断线，不连成假的下降沿；叠加阈值线，方便肉眼判断「多久低于阈值了」。
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
    /** 每个有效点的坐标，画完线再统一画圆点。 */
    private final java.util.ArrayList<Float> px = new java.util.ArrayList<>();
    private final java.util.ArrayList<Float> py = new java.util.ArrayList<>();

    private PulseTable table = PulseTable.empty();
    private int threshold = 55;
    private final int visible = DEFAULT_VISIBLE;
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
        pLine.setStrokeWidth(dp(1f));
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

    /** 喂数据：整张表 + 判定阈值。当前格由表自己提供。 */
    public void setData(PulseTable data, int thr) {
        this.table = data == null ? PulseTable.empty() : data;
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

        // 以当前格为右端点，向左取 visible 格；跨 0 点自动回绕到前一天末尾
        int base = table.curSlot();
        int count = table.isEmpty() ? 0 : Math.min(visible, SlotMath.SLOT_COUNT);

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
            canvas.drawText("还没有曲线数据，先点「测一次」", left + dp(8), (top + bottom) / 2, pText);
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

        // 折线：细线 + 每个有效格一个点
        path.reset();
        px.clear();
        py.clear();
        boolean started = false;
        for (int i = 0; i < count; i++) {
            int slot = SlotMath.slotAt(base, count - 1 - i);
            int v = table.at(slot);
            if (!SlotMath.valid(v)) {
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
            px.add(x);
            py.add(y);
        }
        canvas.drawPath(path, pLine);
        for (int i = 0; i < px.size(); i++) {
            canvas.drawCircle(px.get(i), py.get(i), dp(1.7f), pDot);
        }

        // 底部刻度：每 30 分钟（3 格）一根线；标签放不下就自动按 1h / 2h 稀疏
        pText.setTextSize(sp(8));
        int tickStep = 3;
        int labelStep = tickStep;
        while (stepX * labelStep < dp(38) && labelStep < count) {
            labelStep += tickStep;
        }
        for (int i = count - 1; i >= 0; i -= tickStep) {
            float x = left + stepX * i;
            canvas.drawLine(x, bottom, x, bottom + dp(3), pGrid);
            int back = count - 1 - i;
            if (back % labelStep == 0) {
                String label = SlotMath.clockLabel(SlotMath.slotAt(base, back));
                float tw = pText.measureText(label);
                float tx = Math.max(left, Math.min(right - tw, x - tw / 2));
                canvas.drawText(label, tx, h - dp(6), pText);
            }
        }

        if (footer != null && footer.length() > 0) {
            pText.setTextSize(sp(9));
            canvas.drawText(footer, left, top + sp(9), pText);
        }
    }

    private static float mapY(int v, float top, float bottom, int yMin, int yMax) {
        float t = (v - yMin) / (float) (yMax - yMin);
        t = Math.max(0f, Math.min(1f, t));
        return bottom - (bottom - top) * t;
    }
}
