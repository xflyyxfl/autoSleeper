package com.vlab.autosleeper.core;

/**
 * 判定引擎：策略 + 连续确认闸门。
 *
 * 唯一的睡眠状态持有者。流程只调 {@link #update} 拿 {@link JudgeOutcome}，
 * 然后按里面的 transition 决定发广播/锁屏，不需要自己记「上次通知过没有」。
 */
public final class JudgeEngine {

    private SleepStrategy strategy;
    private final ConsecutiveGate gate;

    public JudgeEngine(SleepStrategy strategy, int confirmRounds) {
        this.strategy = strategy;
        this.gate = new ConsecutiveGate(confirmRounds);
    }

    /**
     * 按配置造一个策略。放在 core 里，避免 core 反向依赖 data 层的 Config。
     *
     * @param absFallbackBpm 基线模式的低心率兜底阈值，传用户的 M
     */
    public static SleepStrategy strategyFor(JudgeKind kind, int threshold,
                                            int dropBpm, double baseline, int absFallbackBpm) {
        if (kind == JudgeKind.BASELINE) {
            return new BaselineStrategy(baseline, dropBpm, absFallbackBpm);
        }
        return new ThresholdStrategy(threshold);
    }

    public SleepStrategy strategy() {
        return strategy;
    }

    public void strategy(SleepStrategy strategy) {
        if (strategy != null) {
            this.strategy = strategy;
        }
    }

    /** 评估一轮，并推进连续确认。 */
    public JudgeOutcome update(PulseWindow window) {
        Verdict verdict = strategy.evaluate(window);
        Transition transition = gate.feed(verdict.candidate);
        return new JudgeOutcome(verdict, transition);
    }

    public boolean sleeping() {
        return gate.latched();
    }

    public int streak() {
        return gate.streak();
    }

    public int need() {
        return gate.need();
    }

    /** 当前基线，仅基线模式有意义；其它模式返回 0。 */
    public double baseline() {
        if (strategy instanceof BaselineStrategy) {
            return ((BaselineStrategy) strategy).baseline();
        }
        return 0d;
    }

    public void reset() {
        gate.reset();
        strategy.reset();
    }
}
