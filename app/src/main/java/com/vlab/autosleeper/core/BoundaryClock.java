package com.vlab.autosleeper.core;

import java.util.Calendar;
import java.util.Locale;

/**
 * 整点对齐的纯计算。没有任何 Android 依赖，也没有副作用，可以直接单测。
 *
 * 以前这套逻辑埋在 GuardService.loop() 里（nextBoundary + 一堆 if），
 * 「差多少秒、要不要跳过」的规则散在循环和 cycle 两处，出了偏差只能靠真机试。
 * 现在一次性算出一个 Plan，流程只照着执行。
 */
public final class BoundaryClock {

    /** 提前多久建立连接：手环空闲 11 分钟必断，扫描/连接要花几秒，留 55 秒余量。 */
    public static final long CONNECT_LEAD_MS = 55_000L;
    /** 关灯必须跨过整点结算时刻，否则这一格不落盘。 */
    public static final long SETTLE_MS = 15_000L;
    /** 唤醒后迟到多少秒就不值得再连表了。 */
    public static final long LATE_TOLERANCE_MS = 20_000L;
    /** 空等时的心跳间隔，防止被手环主动断开。 */
    public static final long PING_MS = 40_000L;

    /** 一轮的时间安排。 */
    public static final class Plan {

        /** 本格结算时刻（对齐到 interval 的整倍数）。 */
        public final long boundaryAt;
        /** 开始亮灯的时刻。 */
        public final long ledOnAt;
        /** 关闭亮灯的时刻。 */
        public final long ledOffAt;
        /** 建立连接的时刻。 */
        public final long connectAt;

        public Plan(long boundaryAt, long ledOnAt, long ledOffAt, long connectAt) {
            this.boundaryAt = boundaryAt;
            this.ledOnAt = ledOnAt;
            this.ledOffAt = ledOffAt;
            this.connectAt = connectAt;
        }

        public long ledSeconds() {
            return Math.max(0L, (ledOffAt - ledOnAt) / 1000L);
        }
    }

    private BoundaryClock() {
    }

    /** 下一个对齐到 intervalMin 整数倍的整点时刻（毫秒）。 */
    public static long nextBoundary(long nowMs, int intervalMin) {
        long step = (long) Math.max(1, intervalMin) * 60_000L;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        long k = (nowMs - dayStart) / step + 1L;
        return dayStart + k * step;
    }

    /**
     * 规划下一轮。
     *
     * 连接和握手要花几秒，如果开灯点已经过去，宁可等下一格，
     * 也不要连上去只亮 0 秒（既不产生数据，还会把刚开的灯立刻关掉）。
     */
    public static Plan plan(long nowMs, int intervalMin, int monitorSec) {
        int monitor = Math.max(60, monitorSec);
        long step = (long) Math.max(1, intervalMin) * 60_000L;
        long boundary = nextBoundary(nowMs, intervalMin);
        long need = (monitor + 25L) * 1000L;
        while (boundary - need < nowMs) {
            boundary += step;
        }
        long ledOn = boundary - monitor * 1000L;
        return new Plan(boundary, ledOn, boundary + SETTLE_MS, ledOn - CONNECT_LEAD_MS);
    }

    /**
     * 唤醒后重新对表。进程可能被系统冻结过，醒来时这一格早就过去了。
     *
     * @return 跳过原因；null 表示可以正常执行
     */
    public static String skipReason(Plan plan, long nowMs) {
        long late = nowMs - plan.ledOnAt;
        if (late > LATE_TOLERANCE_MS) {
            return String.format(Locale.US, "错过开灯点 %d 秒", late / 1000L);
        }
        return null;
    }
}
