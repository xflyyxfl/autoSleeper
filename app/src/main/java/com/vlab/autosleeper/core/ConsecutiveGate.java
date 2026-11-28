package com.vlab.autosleeper.core;

/**
 * 「连续 N 轮成立才认账」的唯一实现。
 *
 * 以前两种判定方式各写了一遍 consecutive / asleep 状态机，外加 Service 里还有
 * 一个 notifiedAsleep，三处状态迟早对不上。现在只有这一个闸门持有睡眠状态，
 * 并且只在状态边沿返回 FELL_ASLEEP / WOKE_UP。
 */
public final class ConsecutiveGate {

    private final int need;
    private int streak;
    private boolean latched;

    public ConsecutiveGate(int need) {
        this.need = Math.max(1, need);
    }

    /**
     * 喂一轮结果。
     *
     * @param pass 本轮是否满足判睡条件
     * @return 状态边沿
     */
    public Transition feed(boolean pass) {
        if (pass) {
            if (streak < need) {
                streak++;
            }
        } else {
            streak = 0;
        }
        boolean next = streak >= need;
        Transition t;
        if (next == latched) {
            t = Transition.NONE;
        } else if (next) {
            t = Transition.FELL_ASLEEP;
        } else {
            t = Transition.WOKE_UP;
        }
        latched = next;
        return t;
    }

    /** 已确认连续几轮。 */
    public int streak() {
        return streak;
    }

    /** 需要连续几轮。 */
    public int need() {
        return need;
    }

    public boolean latched() {
        return latched;
    }

    public void reset() {
        streak = 0;
        latched = false;
    }
}
