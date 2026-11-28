package com.vlab.autosleeper.core;

import java.util.Arrays;
import java.util.Locale;

/**
 * 格号运算与协议常量。
 *
 * 手环把心率按 10 分钟一格聚合，一天 144 格，下标是「当天 00:00 起算的绝对格号」。
 * 手环跨天不会清空昨天的残留，所以任何取数都必须用 {@link #slotAt} 做环形回绕，
 * 不能简单取「数组里最大的有效下标」。
 *
 * 这是全工程唯一一处定义格号规则的地方：取窗口、落盘、画图都从这里拿常量。
 */
public final class SlotMath {

    /** 一天的总格数：144 格 x 10 分钟 = 24 小时。 */
    public static final int SLOT_COUNT = 144;
    /** 每格代表的分钟数。 */
    public static final int SLOT_MINUTES = 10;
    /** 每格的毫秒数。 */
    public static final long SLOT_MS = SLOT_MINUTES * 60L * 1000L;

    /** 该格没有数据。 */
    public static final int NO_DATA = -1;

    /**
     * 有效心率的区间。
     * 采集侧按 25..230 收，展示侧也统一用这一套，避免两处阈值不一致。
     */
    public static final int BPM_MIN = 25;
    public static final int BPM_MAX = 230;

    private SlotMath() {
    }

    /** 把任意整数格号映射回 0..143。 */
    public static int wrap(int slot) {
        int i = slot % SLOT_COUNT;
        return i < 0 ? i + SLOT_COUNT : i;
    }

    /** 以 cur 为基准往前第 back 格的绝对下标，跨 0 点自动回绕。 */
    public static int slotAt(int cur, int back) {
        return wrap(cur - back);
    }

    /** 心率值是否有效。 */
    public static boolean valid(int bpm) {
        return bpm >= BPM_MIN && bpm <= BPM_MAX;
    }

    /** 由「时、分」推出当天格号（0..143）。 */
    public static int slotOf(int hour, int minute) {
        int h = ((hour % 24) + 24) % 24;
        int m = ((minute % 60) + 60) % 60;
        int s = (h * 60 + m) / SLOT_MINUTES;
        return Math.max(0, Math.min(SLOT_COUNT - 1, s));
    }

    /** 格号 -> "HH:mm"，用于日志与坐标轴。 */
    public static String clockLabel(int slot) {
        int min = wrap(slot) * SLOT_MINUTES;
        return String.format(Locale.US, "%02d:%02d", min / 60, min % 60);
    }

    /** 毫秒时刻 -> "HH:mm"。 */
    public static String clockLabel(long atMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(atMs);
        return String.format(Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** 一整天的空格子。 */
    public static int[] emptySlots() {
        int[] a = new int[SLOT_COUNT];
        Arrays.fill(a, NO_DATA);
        return a;
    }
}
