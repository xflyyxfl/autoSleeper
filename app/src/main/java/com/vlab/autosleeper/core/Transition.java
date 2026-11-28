package com.vlab.autosleeper.core;

/**
 * 睡/醒状态的边沿。
 *
 * 全工程只有 {@link ConsecutiveGate} 会产生它，也只有它改变睡眠状态。
 * 这样「广播 ASLEEP 只发一次」「锁屏只锁一次」不再依赖 Service 里另一个
 * {@code notifiedAsleep} 字段，避免两处状态各走各的。
 */
public enum Transition {

    /** 状态没变。 */
    NONE,
    /** 本轮刚从清醒转入睡眠。 */
    FELL_ASLEEP,
    /** 本轮刚从睡眠转回清醒。 */
    WOKE_UP
}
