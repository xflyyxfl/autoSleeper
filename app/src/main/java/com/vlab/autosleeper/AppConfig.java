package com.vlab.autosleeper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 参数与判定的持久化状态。
 */
public class AppConfig {

    private static final String PREF = "autosleeper";

    private final SharedPreferences sp;

    public AppConfig(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String addr() {
        String v = sp.getString("addr", "");
        if (v == null || v.trim().isEmpty()) {
            return "78:02:B7:6F:21:0B";
        }
        return v.trim();
    }

    public void setAddr(String v) {
        sp.edit().putString("addr", v).apply();
    }

    public int intervalMin() {
        return clamp(sp.getInt("interval", 10), 1, 120);
    }

    public void setIntervalMin(int v) {
        sp.edit().putInt("interval", clamp(v, 1, 120)).apply();
    }

    /**
     * 每个周期里让手环开着心率监测的秒数。
     *
     * 固件的规矩：一格（10 分钟）内要有约 4 分钟以上的有效采样，
     * 并且结算时刻（每 10 分钟整）监测还开着，这一格才会被写入。
     * 所以这里不是"采样时长"，而是"亮灯时长"：每 10 分钟亮 monitorSec 秒，
     * 卡在整点后 15 秒关闭，平均占空比 = monitorSec / 600。
     * 默认 150 秒 ≈ 25%，续航损失比全程常亮小得多。
     */
    public int monitorSec() {
        return clamp(sp.getInt("monitor", 150), 60, 600);
    }

    public void setMonitorSec(int v) {
        sp.edit().putInt("monitor", clamp(v, 60, 600)).apply();
    }

    /**
     * 判定窗口用最近几格心率。每格 10 分钟，默认 3 格 = 30 分钟。
     * 窗口太短噪声大，太长则对入睡反应迟钝。
     */
    public int windowSlots() {
        return clamp(sp.getInt("windowSlots", 3), 2, 8);
    }

    public void setWindowSlots(int v) {
        sp.edit().putInt("windowSlots", clamp(v, 2, 8)).apply();
    }

    /** 判定阈值：相对基线下降多少 bpm 记满分。 */
    public int dropBpm() {
        return clamp(sp.getInt("drop", 5), 2, 20);
    }

    /** 连续几个窗口成立才判睡。 */
    public int needWindows() {
        return clamp(sp.getInt("windows", 2), 1, 5);
    }

    public double baseline() {
        return sp.getFloat("baseline", 0f);
    }

    public void setBaseline(double v) {
        sp.edit().putFloat("baseline", (float) v).apply();
    }

    public void resetBaseline() {
        sp.edit().remove("baseline").apply();
    }

    public boolean onlyAtNight() {
        return sp.getBoolean("onlyNight", false);
    }

    public void setOnlyAtNight(boolean v) {
        sp.edit().putBoolean("onlyNight", v).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
