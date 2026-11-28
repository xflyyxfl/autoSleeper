package com.vlab.autosleeper.core;

import java.util.Calendar;

/**
 * 一次拉取得到的整张心率表，不可变。
 *
 * 以前这张表散在 {@code BandClient.PulseHistory}、{@code PulseStore} 两处，
 * 各自维护 slots / cur / lastIndex，还要靠调用方自己算窗口。
 * 现在表本身就是唯一数据源：取窗口、算陈旧度、落盘都走它的方法。
 */
public final class PulseTable {

    private static final PulseTable EMPTY =
            new PulseTable(SlotMath.emptySlots(), 0, -1, -1, 0L);

    private final int[] slots;
    private final int curSlot;
    private final int watchHour;
    private final int watchMinute;
    private final long capturedAt;

    private PulseTable(int[] slots, int curSlot, int watchHour, int watchMinute, long capturedAt) {
        this.slots = slots;
        this.curSlot = curSlot;
        this.watchHour = watchHour;
        this.watchMinute = watchMinute;
        this.capturedAt = capturedAt;
    }

    public static PulseTable empty() {
        return EMPTY;
    }

    /**
     * 用手环上报的时刻定位当前格；手环没报时刻时退回本机时间。
     *
     * @param slots  长度应为 {@link SlotMath#SLOT_COUNT}，越界部分忽略
     * @param hour   手环时刻的小时，-1 表示未知
     * @param minute 手环时刻的分钟，-1 表示未知
     */
    public static PulseTable of(int[] slots, int hour, int minute, long nowMs) {
        int[] copy = SlotMath.emptySlots();
        if (slots != null) {
            System.arraycopy(slots, 0, copy, 0, Math.min(slots.length, copy.length));
        }
        int cur;
        if (hour >= 0 && hour < 24 && minute >= 0 && minute < 60) {
            cur = SlotMath.slotOf(hour, minute);
        } else {
            cur = currentSlotOf(nowMs);
        }
        return new PulseTable(copy, cur, hour, minute, nowMs);
    }

    /** 从磁盘恢复：只有格子数据，当前格按本机时间重算（不能用文件里那份，可能已经是昨天的）。 */
    public static PulseTable restore(int[] slots, long nowMs) {
        return restore(slots, -1, -1, nowMs);
    }

    /**
     * 从磁盘恢复，同时保留存档里记录的手表时刻（仅用于显示）。
     * 当前格一律按本机时间算，否则界面上的「距今多久」会错。
     */
    public static PulseTable restore(int[] slots, int watchHour, int watchMinute, long nowMs) {
        int[] copy = SlotMath.emptySlots();
        if (slots != null) {
            System.arraycopy(slots, 0, copy, 0, Math.min(slots.length, copy.length));
        }
        return new PulseTable(copy, currentSlotOf(nowMs), watchHour, watchMinute, nowMs);
    }

    public static int currentSlotOf(long nowMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        return SlotMath.slotOf(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    public int curSlot() {
        return curSlot;
    }

    public int watchHour() {
        return watchHour;
    }

    public int watchMinute() {
        return watchMinute;
    }

    public long capturedAt() {
        return capturedAt;
    }

    /** 格子副本，长度固定 SLOT_COUNT。 */
    public int[] slots() {
        return slots.clone();
    }

    public int at(int slot) {
        return slots[SlotMath.wrap(slot)];
    }

    public int validCount() {
        int n = 0;
        for (int i = 0; i < slots.length; i++) {
            if (SlotMath.valid(slots[i])) {
                n++;
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return validCount() == 0;
    }

    /** 从当前格往前找最近一个有效格的下标；全空返回 -1。 */
    public int lastValidSlot() {
        for (int k = 0; k < SlotMath.SLOT_COUNT; k++) {
            int i = SlotMath.slotAt(curSlot, k);
            if (SlotMath.valid(slots[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 最近一个有效格距今多少分钟；全空返回 Integer.MAX_VALUE。 */
    public int ageMinutes() {
        for (int k = 0; k < SlotMath.SLOT_COUNT; k++) {
            int i = SlotMath.slotAt(curSlot, k);
            if (SlotMath.valid(slots[i])) {
                return k * SlotMath.SLOT_MINUTES;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 以当前格为右端点，取最近 n 格。空洞保留为 NO_DATA。
     *
     * 这是唯一取窗口的入口：不再有「按有效值取最近 3 个」这种会跨小时的语义。
     */
    public PulseWindow window(int n) {
        int size = Math.max(1, Math.min(n, SlotMath.SLOT_COUNT));
        int[] out = new int[size];
        for (int k = size - 1; k >= 0; k--) {
            out[size - 1 - k] = slots[SlotMath.slotAt(curSlot, k)];
        }
        return PulseWindow.of(out);
    }

    /** 手环时间标签 "HH:mm"；未知时返回 "--:--"。 */
    public String watchLabel() {
        if (watchHour < 0 || watchMinute < 0) {
            return "--:--";
        }
        return String.format(java.util.Locale.US, "%02d:%02d", watchHour, watchMinute);
    }
}
