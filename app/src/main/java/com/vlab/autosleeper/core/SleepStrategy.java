package com.vlab.autosleeper.core;

/**
 * 判定策略。
 *
 * 约定：{@link #evaluate} 只根据窗口算「这一轮是否满足判睡条件」，
 * 不自己维护 sleeping 状态、不做「连续 N 轮」的确认。
 * 那是 {@link ConsecutiveGate} 的职责，两个策略共用同一套，结论才可比。
 */
public interface SleepStrategy {

    JudgeKind kind();

    /** 评估一个窗口。永不返回 null。 */
    Verdict evaluate(PulseWindow window);

    /** 清空内部状态（基线/上一轮中位数）。 */
    void reset();
}
