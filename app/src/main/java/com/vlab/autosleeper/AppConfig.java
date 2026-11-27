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
     * 实测出来的硬约束：手环要往当前格子里写一条心率，需要**连续采样约 3.5 分钟以上**。
     * 把历史数据按亮灯时长排一下就看得出来：
     *   2.3 分钟 → 没写   2.4 分钟 → 没写   2.5 分钟 → 没写   2.7 分钟 → 没写
     *   3.7 分钟 → 写入
     * 所以默认给 240 秒（4 分钟），并且要跨过整点结算时刻。
     * 低于 210 秒基本是白亮，一格都写不进去。
     */
    public int monitorSec() {
        int v = clamp(sp.getInt("monitor", 240), 120, 600);
        // 不能比一个周期还长，否则两个窗口会重叠
        int max = intervalMin() * 60 - 45;
        return Math.max(120, Math.min(v, max));
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

    // ---------------- 简单阈值判定 ----------------

    /**
     * 睡眠阈值 M：心率低于这个值才算"睡着"。
     * 成年人的睡眠心率常见 45~60，清醒静息 60~75，默认 55 比较稳。
     */
    public int threshold() {
        return clamp(sp.getInt("threshold", 55), 35, 95);
    }

    public void setThreshold(int v) {
        sp.edit().putInt("threshold", clamp(v, 35, 95)).apply();
    }

    /** 持续多少分钟低于阈值才算入睡。每格 10 分钟，所以默认 20 分钟 = 2 格。 */
    public int holdMinutes() {
        return clamp(sp.getInt("hold", 20), 10, 120);
    }

    public void setHoldMinutes(int v) {
        sp.edit().putInt("hold", clamp(v, 10, 120)).apply();
    }

    /** 需要连续几轮判定成立才真正触发（防止单次误采）。 */
    public int needRounds() {
        return clamp(sp.getInt("needRounds", 2), 1, 5);
    }

    public void setNeedRounds(int v) {
        sp.edit().putInt("needRounds", clamp(v, 1, 5)).apply();
    }

    /** 判定方式：0 = 简单阈值（默认），1 = 相对基线的四票打分。 */
    public int judgeMode() {
        return clamp(sp.getInt("judgeMode", 0), 0, 1);
    }

    public void setJudgeMode(int v) {
        sp.edit().putInt("judgeMode", clamp(v, 0, 1)).apply();
    }

    // ---------------- 运行模式 ----------------

    /**
     * 测量模式：0 = 脉冲省电（每 10 分钟只在整点前亮灯 monitorSec 秒），
     *          1 = 连续监测（心率灯常开，曲线连续，耗电明显增加）。
     */
    public int measureMode() {
        return clamp(sp.getInt("measureMode", 0), 0, 1);
    }

    public void setMeasureMode(int v) {
        sp.edit().putInt("measureMode", clamp(v, 0, 1)).apply();
    }

    /** 判定入睡后是否自动锁屏。 */
    public boolean autoLock() {
        return sp.getBoolean("autoLock", true);
    }

    public void setAutoLock(boolean v) {
        sp.edit().putBoolean("autoLock", v).apply();
    }

    /** 开机后是否自动恢复守护。 */
    public boolean resumeOnBoot() {
        return sp.getBoolean("resumeOnBoot", true);
    }

    public void setResumeOnBoot(boolean v) {
        sp.edit().putBoolean("resumeOnBoot", v).apply();
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
