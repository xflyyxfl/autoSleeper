package com.vlab.autosleeper.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.core.JudgeKind;
import com.vlab.autosleeper.core.MeasureMode;

import java.util.Map;

/**
 * 参数读写。clamp 只在这里做一次，setter 和 getter 不会再有不一样的范围。
 *
 * 所有键都来自 {@link ConfigSpec}，这里不出现任何字符串键名。
 */
public final class Config {

    private final SharedPreferences sp;

    public Config(Context c) {
        sp = c.getApplicationContext()
                .getSharedPreferences(ConfigSpec.PREF, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 通用读写

    /** 按 Key 读整数（已 clamp 到声明范围）。界面绑定用。 */
    public int getInt(ConfigSpec.Key<Integer> key) {
        return readInt(key);
    }

    public void putInt(ConfigSpec.Key<Integer> key, int value) {
        writeInt(key, value);
    }

    public boolean getBool(ConfigSpec.Key<Boolean> key) {
        return readBool(key);
    }

    public void putBool(ConfigSpec.Key<Boolean> key, boolean value) {
        writeBool(key, value);
    }

    public String getString(ConfigSpec.Key<String> key) {
        return readStr(key);
    }

    public void putString(ConfigSpec.Key<String> key, String value) {
        writeStr(key, value);
    }

    private int readInt(ConfigSpec.Key<Integer> key) {
        int v = sp.getInt(key.name, key.defInt());
        return clamp(v, key.min, key.max);
    }

    private void writeInt(ConfigSpec.Key<Integer> key, int value) {
        sp.edit().putInt(key.name, clamp(value, key.min, key.max)).apply();
    }

    private boolean readBool(ConfigSpec.Key<Boolean> key) {
        return sp.getBoolean(key.name, key.defBool());
    }

    private void writeBool(ConfigSpec.Key<Boolean> key, boolean value) {
        sp.edit().putBoolean(key.name, value).apply();
    }

    private String readStr(ConfigSpec.Key<String> key) {
        String v = sp.getString(key.name, key.defString());
        return v == null ? key.defString() : v;
    }

    private void writeStr(ConfigSpec.Key<String> key, String value) {
        sp.edit().putString(key.name, value).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------ 手环与采样

    public String addr() {
        String v = readStr(ConfigSpec.ADDR);
        if (v == null || v.trim().isEmpty()) {
            return ConfigSpec.ADDR.defString();
        }
        return v.trim();
    }

    public void setAddr(String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) {
            writeStr(ConfigSpec.ADDR, v);
        }
    }

    public int intervalMin() {
        return readInt(ConfigSpec.INTERVAL_MIN);
    }

    public void setIntervalMin(int value) {
        writeInt(ConfigSpec.INTERVAL_MIN, value);
    }

    /**
     * 实际生效的亮灯时长。
     * 不能比一个周期还长，否则两个窗口会重叠；间隔小于 6 分钟时按 6 分钟算。
     */
    public int monitorSec() {
        int v = readInt(ConfigSpec.MONITOR_SEC);
        int effectiveInterval = Math.max(6, intervalMin());
        int cap = effectiveInterval * 60 - 45;
        return Math.max(ConfigSpec.MONITOR_SEC.min, Math.min(v, cap));
    }

    public void setMonitorSec(int value) {
        writeInt(ConfigSpec.MONITOR_SEC, value);
    }

    public MeasureMode measureMode() {
        return MeasureMode.parse(readStr(ConfigSpec.MEASURE_KIND));
    }

    public void setMeasureMode(MeasureMode mode) {
        writeStr(ConfigSpec.MEASURE_KIND, mode == null ? MeasureMode.PULSE.name() : mode.name());
    }

    // ------------------------------------------------------------ 判定

    public JudgeKind judgeKind() {
        return JudgeKind.parse(readStr(ConfigSpec.JUDGE_KIND));
    }

    public void setJudgeKind(JudgeKind kind) {
        writeStr(ConfigSpec.JUDGE_KIND, kind == null ? JudgeKind.THRESHOLD.name() : kind.name());
    }

    public int threshold() {
        return readInt(ConfigSpec.THRESHOLD);
    }

    public void setThreshold(int value) {
        writeInt(ConfigSpec.THRESHOLD, value);
    }

    public int holdSlots() {
        return readInt(ConfigSpec.HOLD_SLOTS);
    }

    public void setHoldSlots(int value) {
        writeInt(ConfigSpec.HOLD_SLOTS, value);
    }

    /** 窗口时长，单位分钟，仅用于界面展示。 */
    public int holdMinutes() {
        return holdSlots() * 10;
    }

    public int confirmRounds() {
        return readInt(ConfigSpec.CONFIRM_ROUNDS);
    }

    public void setConfirmRounds(int value) {
        writeInt(ConfigSpec.CONFIRM_ROUNDS, value);
    }

    public int dropBpm() {
        return readInt(ConfigSpec.DROP_BPM);
    }

    public void setDropBpm(int value) {
        writeInt(ConfigSpec.DROP_BPM, value);
    }

    // ------------------------------------------------------------ 行为

    public boolean autoLock() {
        return readBool(ConfigSpec.AUTO_LOCK);
    }

    public void setAutoLock(boolean value) {
        writeBool(ConfigSpec.AUTO_LOCK, value);
    }

    public boolean resumeOnBoot() {
        return readBool(ConfigSpec.RESUME_ON_BOOT);
    }

    public void setResumeOnBoot(boolean value) {
        writeBool(ConfigSpec.RESUME_ON_BOOT, value);
    }

    // ------------------------------------------------------------ 悬浮球

    public boolean floatEnabled() {
        return readBool(ConfigSpec.FLOAT_ENABLED);
    }

    public void setFloatEnabled(boolean value) {
        writeBool(ConfigSpec.FLOAT_ENABLED, value);
    }

    public int floatX() {
        return readInt(ConfigSpec.FLOAT_X);
    }

    public void setFloatX(int value) {
        writeInt(ConfigSpec.FLOAT_X, value);
    }

    public int floatY() {
        return readInt(ConfigSpec.FLOAT_Y);
    }

    public void setFloatY(int value) {
        writeInt(ConfigSpec.FLOAT_Y, value);
    }

    public int floatAlpha() {
        return readInt(ConfigSpec.FLOAT_ALPHA);
    }

    public void setFloatAlpha(int value) {
        writeInt(ConfigSpec.FLOAT_ALPHA, value);
    }

    // ------------------------------------------------------------ 基线

    /** 上次学到的清醒基线；0 表示还没有。 */
    public double baseline() {
        return sp.getFloat("baseline", 0f);
    }

    public void setBaseline(double value) {
        sp.edit().putFloat("baseline", (float) value).apply();
    }

    public void resetBaseline() {
        sp.edit().remove("baseline").apply();
    }

    // ------------------------------------------------------------ 迁移

    /**
     * 把旧版本（v1.x）的参数换算到新键上，只跑一次。
     *
     * 旧键：hold（分钟）、rounds / windows（连续轮数）、windowSlots、
     *       judgeMode / measureMode（int 0/1）。
     * 新键换成不同名字、不同存储类型，靠 getAll() 逐个判类型，避免类型冲突崩溃。
     */
    public void migrate() {
        if (sp.getBoolean(ConfigSpec.MIGRATED_KEY, false)) {
            return;
        }
        try {
            Map<String, ?> all = sp.getAll();

            Object hold = all.get("hold");
            if (hold instanceof Integer) {
                int minutes = ((Integer) hold).intValue();
                writeInt(ConfigSpec.HOLD_SLOTS, Math.max(1, minutes / 10));
            }

            Object rounds = all.get("rounds");
            if (!(rounds instanceof Integer)) {
                rounds = all.get("windows");
            }
            if (rounds instanceof Integer) {
                writeInt(ConfigSpec.CONFIRM_ROUNDS, ((Integer) rounds).intValue());
            }

            Object judge = all.get("judgeMode");
            if (judge instanceof Integer) {
                boolean baseline = ((Integer) judge).intValue() == 1;
                setJudgeKind(baseline ? JudgeKind.BASELINE : JudgeKind.THRESHOLD);
            }

            Object measure = all.get("measureMode");
            if (measure instanceof Integer) {
                boolean continuous = ((Integer) measure).intValue() == 1;
                setMeasureMode(continuous ? MeasureMode.CONTINUOUS : MeasureMode.PULSE);
            }

            LogBus.log("已把旧版参数迁移到新结构");
        } catch (Throwable t) {
            LogBus.log("参数迁移跳过: " + t.getMessage());
        }
        sp.edit().putBoolean(ConfigSpec.MIGRATED_KEY, true).apply();
    }
}
