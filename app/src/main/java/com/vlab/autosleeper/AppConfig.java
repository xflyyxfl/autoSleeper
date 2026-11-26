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

    public int sampleSec() {
        return clamp(sp.getInt("sample", 45), 10, 180);
    }

    public void setSampleSec(int v) {
        sp.edit().putInt("sample", clamp(v, 10, 180)).apply();
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
