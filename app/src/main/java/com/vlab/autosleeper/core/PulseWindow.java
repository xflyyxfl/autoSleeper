package com.vlab.autosleeper.core;

import java.util.Arrays;

/**
 * 一个判定窗口：最近若干格的心率，允许包含空洞（{@link SlotMath#NO_DATA}）。
 *
 * 这是全工程唯一一处「取窗口 + 算统计量」的地方。
 * 以前 GuardService、SleepJudge、PulseHistory、ThresholdJudge 各写了一份，
 * 而且有的保留空洞、有的把空洞删掉，导致两种判定方式对同一份数据给出不同结论。
 *
 * 现在统一规则：窗口长度必须等于请求的格数，空洞用 NO_DATA 占位；
 * 统计量只对有效值计算，是否容忍空洞由调用方（策略）显式决定。
 */
public final class PulseWindow {

    public static final PulseWindow EMPTY = new PulseWindow(new int[0]);

    private final int[] values;

    private PulseWindow(int[] values) {
        this.values = values;
    }

    public static PulseWindow of(int[] values) {
        if (values == null || values.length == 0) {
            return EMPTY;
        }
        return new PulseWindow(values.clone());
    }

    public int size() {
        return values.length;
    }

    public boolean isEmpty() {
        return values.length == 0;
    }

    public int at(int index) {
        return values[index];
    }

    /** 有效值个数。 */
    public int filled() {
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (SlotMath.valid(values[i])) {
                n++;
            }
        }
        return n;
    }

    /** 有没有空洞（存在未采到的格）。 */
    public boolean hasGap() {
        return filled() < values.length;
    }

    /** 是否整段连续有效。 */
    public boolean contiguous() {
        return values.length > 0 && filled() == values.length;
    }

    private int[] sortedFilled() {
        int[] a = new int[filled()];
        int k = 0;
        for (int i = 0; i < values.length; i++) {
            if (SlotMath.valid(values[i])) {
                a[k++] = values[i];
            }
        }
        Arrays.sort(a);
        return a;
    }

    /** 中位数。没有有效值时返回 0（调用方应先判断 {@link #filled()}）。 */
    public int median() {
        int[] a = sortedFilled();
        return a.length == 0 ? 0 : a[a.length / 2];
    }

    public int min() {
        int[] a = sortedFilled();
        return a.length == 0 ? 0 : a[0];
    }

    public int max() {
        int[] a = sortedFilled();
        return a.length == 0 ? 0 : a[a.length - 1];
    }

    /** 标准差（总体标准差，除以 n）。字段语义不会再被塞成极差。 */
    public double sd() {
        int[] a = sortedFilled();
        if (a.length == 0) {
            return 0d;
        }
        double mean = 0d;
        for (int i = 0; i < a.length; i++) {
            mean += a[i];
        }
        mean /= a.length;
        double var = 0d;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - mean;
            var += d * d;
        }
        return Math.sqrt(var / a.length);
    }

    /** 极差，阈值模式下用来描述「峰值到谷值」。 */
    public int range() {
        return filled() == 0 ? 0 : (max() - min());
    }

    /** 复制一份，给需要排序/改动的调用方。 */
    public int[] values() {
        return values.clone();
    }
}
