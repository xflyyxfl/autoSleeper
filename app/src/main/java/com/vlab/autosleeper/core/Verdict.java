package com.vlab.autosleeper.core;

import java.util.Locale;

/**
 * 一轮判定的结果，不可变。
 *
 * 以前 {@code ThresholdJudge} 借用了 {@code SleepJudge.Result}，
 * 把极差塞进名叫 sd 的字段、score 恒为 0，窗口全空时 min/max 还会溢出。
 * 现在两个策略都只产出这个类型，字段语义唯一：sd 就是标准差。
 */
public final class Verdict {

    /** 中位心率。 */
    public final int median;
    public final int min;
    public final int max;
    /** 标准差。 */
    public final double sd;
    /** 基线模式的得分 0..100；阈值模式下为 100（满足）或 0（不满足）。 */
    public final int score;
    /** 本轮是否满足「该判睡」的条件（还要经连续确认才会真正判睡）。 */
    public final boolean candidate;
    public final Reason reason;
    public final JudgeKind by;

    public Verdict(int median, int min, int max, double sd,
                   int score, boolean candidate, Reason reason, JudgeKind by) {
        this.median = median;
        this.min = min;
        this.max = max;
        this.sd = sd;
        this.score = score;
        this.candidate = candidate;
        this.reason = reason == null ? Reason.NOT_ENOUGH : reason;
        this.by = by == null ? JudgeKind.THRESHOLD : by;
    }

    public static Verdict none(Reason reason, JudgeKind by) {
        return new Verdict(0, 0, 0, 0d, 0, false, reason, by);
    }

    /** 供界面/日志直接显示的一句话。 */
    public String text() {
        return reason.label();
    }

    /** 供日志用的紧凑形态。 */
    public String describe() {
        return String.format(Locale.US, "中位=%d 峰=%d 谷=%d sd=%.1f 得分=%d %s",
                median, max, min, sd, score, reason.label());
    }
}
