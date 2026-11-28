package com.vlab.autosleeper.core;

/**
 * 一轮判定的完整产物：结果 + 状态边沿。
 *
 * 调用方只看 {@link #transition()} 决定要不要发广播/锁屏，
 * 不需要再自己维护「上次是不是已经通知过」。
 */
public final class JudgeOutcome {

    public final Verdict verdict;
    public final Transition transition;

    public JudgeOutcome(Verdict verdict, Transition transition) {
        this.verdict = verdict;
        this.transition = transition == null ? Transition.NONE : transition;
    }

    public Verdict verdict() {
        return verdict;
    }

    public Transition transition() {
        return transition;
    }

    public boolean fellAsleep() {
        return transition == Transition.FELL_ASLEEP;
    }

    public boolean wokeUp() {
        return transition == Transition.WOKE_UP;
    }
}
