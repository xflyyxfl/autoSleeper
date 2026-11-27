package com.vlab.autosleeper;

import java.util.Arrays;

/**
 * 最近一次拉取到的曲线与判定结果，进程内共享，供界面直接读取。
 *
 * GuardService 每轮写入，MainActivity 每 3 秒取一次快照重绘。
 * 全部字段 volatile，写入方是单个后台线程，读取方是 UI 线程，不需要加锁。
 */
public class PulseStore {

    public static final int SLOTS = BandClient.SLOT_COUNT;

    private static int[] emptySlots() {
        int[] a = new int[SLOTS];
        Arrays.fill(a, BandClient.NO_DATA);
        return a;
    }

    /** 长度 SLOTS，值 30..220，-1 表示空格。 */
    public static volatile int[] slots = emptySlots();
    public static volatile int validCount = 0;
    public static volatile int lastIndex = -1;
    public static volatile long updatedAt = 0L;

    /** 手表上报的采集时刻。 */
    public static volatile int hour = -1;
    public static volatile int minute = -1;

    /** 最近一次判定的结果。 */
    public static volatile int median = 0;
    public static volatile int threshold = 55;
    public static volatile boolean asleep = false;
    public static volatile String note = "";

    /** 最近一次写入的原始曲线。 */
    public static void update(int[] s, int valid, int lastIdx, int h, int m) {
        if (s == null || s.length == 0) {
            return;
        }
        int[] copy = new int[SLOTS];
        Arrays.fill(copy, BandClient.NO_DATA);
        System.arraycopy(s, 0, copy, 0, Math.min(s.length, SLOTS));
        slots = copy;
        validCount = valid;
        lastIndex = lastIdx;
        hour = h;
        minute = m;
        updatedAt = System.currentTimeMillis();
    }

    /** 判定结果。 */
    public static void setVerdict(int med, int thr, boolean sleep, String why) {
        median = med;
        threshold = thr;
        asleep = sleep;
        note = why == null ? "" : why;
    }

    public static int[] snapshot() {
        return slots.clone();
    }

    public static boolean hasData() {
        return validCount > 0;
    }
}
