package com.vlab.autosleeper.core;

/**
 * 简单阈值判定：最近 N 格心率都低于 M，且没有明显波动，就认为这一轮成立。
 *
 * 这是默认策略，因为规则最好解释：
 *   「连续 20 分钟心率都低于 55」比四票打分直观得多。
 *
 * 关键点：窗口必须整段连续有效。只要有空洞，就不能断言「持续低于」，
 * 这时候直接返回 GAP，让用户在日志里看到真实原因，而不是被静默判睡。
 */
public final class ThresholdStrategy implements SleepStrategy {

    private final int threshold;
    /** 允许峰值比阈值高多少还认账（默认 8），避免一格抖动把整段判掉。 */
    private final int peakSlack;

    public ThresholdStrategy(int threshold) {
        this(threshold, 8);
    }

    public ThresholdStrategy(int threshold, int peakSlack) {
        this.threshold = Math.max(30, threshold);
        this.peakSlack = Math.max(0, peakSlack);
    }

    public int threshold() {
        return threshold;
    }

    @Override
    public JudgeKind kind() {
        return JudgeKind.THRESHOLD;
    }

    @Override
    public void reset() {
        // 无状态
    }

    @Override
    public Verdict evaluate(PulseWindow window) {
        if (window == null || window.isEmpty()) {
            return Verdict.none(Reason.NOT_ENOUGH, JudgeKind.THRESHOLD);
        }
        if (window.filled() == 0) {
            return Verdict.none(Reason.NOT_ENOUGH, JudgeKind.THRESHOLD);
        }
        if (window.hasGap()) {
            return new Verdict(window.median(), window.min(), window.max(), window.sd(),
                    0, false, Reason.GAP, JudgeKind.THRESHOLD);
        }

        int median = window.median();
        int peak = window.max();
        boolean low = median < threshold && peak < threshold + peakSlack;

        Reason reason;
        if (low) {
            reason = Reason.BELOW;
        } else if (median >= threshold) {
            reason = Reason.ABOVE;
        } else {
            reason = Reason.VOLATILE;
        }

        return new Verdict(median, window.min(), peak, window.sd(),
                low ? 100 : 0, low, reason, JudgeKind.THRESHOLD);
    }
}
