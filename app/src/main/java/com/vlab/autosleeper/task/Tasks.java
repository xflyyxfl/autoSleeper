package com.vlab.autosleeper.task;

import android.content.Intent;

import com.vlab.autosleeper.FloatWindow;
import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.LockHelper;
import com.vlab.autosleeper.band.BandError;
import com.vlab.autosleeper.band.BandException;
import com.vlab.autosleeper.band.BandSession;
import com.vlab.autosleeper.core.JudgeEngine;
import com.vlab.autosleeper.core.JudgeOutcome;
import com.vlab.autosleeper.core.PulseTable;
import com.vlab.autosleeper.core.PulseWindow;
import com.vlab.autosleeper.core.SlotMath;
import com.vlab.autosleeper.core.Transition;
import com.vlab.autosleeper.core.Verdict;
import com.vlab.autosleeper.data.EventStore;
import com.vlab.autosleeper.data.PulseRepository;

import java.util.Locale;

/**
 * 全部原子操作。
 *
 * 每个类只做一件事，可以单独调用，也可以被流程任意组合：
 *
 *   Connect → LedOn → AwaitLedOff → LedOff → FetchPulse → Judge → Publish → AutoLock
 *
 * 单次测量和守护循环的差别只在「前面那段怎么安排时间」，后面这半段完全一样。
 */
public final class Tasks {

    // ------------------------------------------------------------ 对外广播

    public static final String BC_STATE = "com.vlab.autosleeper.STATE";
    public static final String BC_ASLEEP = "com.vlab.autosleeper.ASLEEP";
    public static final String BC_AWAKE = "com.vlab.autosleeper.AWAKE";

    private Tasks() {
    }

    private static BandSession session(TaskContext ctx) throws BandException {
        if (ctx.session == null) {
            throw new BandException(BandError.DISCONNECTED, "没有可用连接");
        }
        return ctx.session;
    }

    // ------------------------------------------------------------ 连接与灯

    /** 建立连接。成功后会接管会话关闭的责任（ownsSession）。 */
    public static final class Connect implements Task {

        private final long timeoutMs;

        public Connect() {
            this(15000L);
        }

        public Connect(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String name() {
            return "连接";
        }

        @Override
        public OpResult run(TaskContext ctx) throws Exception {
            if (ctx.session != null && ctx.session.alive()) {
                return OpResult.skip("已有可用连接");
            }
            if (ctx.session != null) {
                ctx.session.close();
                ctx.session = null;
            }
            ctx.session = BandSession.open(ctx.app, ctx.cfg.addr(), timeoutMs);
            ctx.ownsSession = true;
            return OpResult.ok("已连接 " + ctx.cfg.addr());
        }
    }

    /** 开启心率监测（LED 亮，手环开始往当前格累计采样）。 */
    public static final class LedOn implements Task {

        @Override
        public String name() {
            return "开灯";
        }

        @Override
        public OpResult run(TaskContext ctx) throws Exception {
            session(ctx).ledOn();
            return OpResult.ok("已开灯");
        }
    }

    /** 关闭心率监测。注意固件要求 payload 是 18 02，不是 18 00。 */
    public static final class LedOff implements Task {

        @Override
        public String name() {
            return "关灯";
        }

        @Override
        public OpResult run(TaskContext ctx) throws Exception {
            session(ctx).ledOff();
            return OpResult.ok("已关灯");
        }
    }

    /** 等到计划的开灯时刻。 */
    public static final class AwaitLedOn implements Task {

        @Override
        public String name() {
            return "等待开灯时刻";
        }

        @Override
        public OpResult run(TaskContext ctx) throws Exception {
            session(ctx).awaitUntil(ctx.ledOnAt);
            return OpResult.ok("已到开灯时刻");
        }
    }

    /** 等到计划的关灯时刻（必须跨过整点结算，否则这一格不落盘）。 */
    public static final class AwaitLedOff implements Task {

        @Override
        public String name() {
            return "等待关灯时刻";
        }

        @Override
        public OpResult run(TaskContext ctx) throws Exception {
            session(ctx).awaitUntil(ctx.ledOffAt);
            return OpResult.ok("已到关灯时刻");
        }
    }

    // ------------------------------------------------------------ 数据

    /** 拉取曲线 → 存内存 → 落盘 → 刷悬浮球。所有拉取的唯一实现。 */
    public static final class FetchPulse implements Task {

        private final long timeoutMs;

        public FetchPulse() {
            this(12000L);
        }

        public FetchPulse(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String name() {
            return "拉取曲线";
        }

        @Override
        public OpResult run(TaskContext ctx) throws Exception {
            PulseTable table = session(ctx).fetchPulse(timeoutMs);
            ctx.table = table;
            PulseRepository.publishTable(table);
            PulseRepository.save(ctx.app, table);
            FloatWindow.refresh();

            int last = table.lastValidSlot();
            int age = table.ageMinutes();
            LogBus.log(String.format(Locale.US,
                    "曲线 %d 格有效，最后一格 %s（%s）",
                    table.validCount(),
                    last >= 0 ? SlotMath.clockLabel(last) : "--",
                    age == Integer.MAX_VALUE ? "无" : age + " 分钟前"));
            return OpResult.ok("曲线 " + table.validCount() + " 格");
        }
    }

    /** 判定。窗口长度取配置里的 holdSlots，两种判定方式共用同一窗口。 */
    public static final class Judge implements Task {

        private final JudgeEngine injected;

        public Judge() {
            this(null);
        }

        public Judge(JudgeEngine engine) {
            this.injected = engine;
        }

        @Override
        public String name() {
            return "判定";
        }

        @Override
        public OpResult run(TaskContext ctx) {
            if (ctx.table == null || ctx.table.isEmpty()) {
                return OpResult.fail(OpResult.Code.NO_DATA, "没有曲线可判定");
            }
            JudgeEngine engine = injected != null ? injected : ctx.engine;
            if (engine == null) {
                return OpResult.fail(OpResult.Code.ERROR, "未配置判定引擎");
            }

            int slots = ctx.cfg.holdSlots();

            // 数据太旧就不判：窗口若停在几十分钟前，拿它判睡并锁屏是错的。
            // 容差取「窗口时长 + 10 分钟」，最少 30 分钟。
            int age = ctx.table.ageMinutes();
            int limit = Math.max(30, slots * SlotMath.SLOT_MINUTES + 10);
            if (age != Integer.MAX_VALUE && age > limit) {
                LogBus.log(String.format(Locale.US,
                        "数据太旧：最后一格 %s（%d 分钟前，容差 %d），本轮不判定",
                        ctx.table.settledLabel(), age, limit));
                return OpResult.skip("数据太旧");
            }

            PulseWindow window = ctx.table.window(slots);
            JudgeOutcome outcome = engine.update(window);

            ctx.verdict = outcome.verdict();
            ctx.transition = outcome.transition();
            ctx.sleeping = engine.sleeping();

            PulseRepository.publishVerdict(outcome.verdict(), engine.sleeping());
            if (engine.baseline() > 0d) {
                ctx.cfg.setBaseline(engine.baseline());
            }
            FloatWindow.refresh();

            LogBus.log("判定窗口 " + slots + " 格（截至 " + ctx.table.settledLabel()
                    + "）-> " + outcome.verdict().describe()
                    + (engine.sleeping() ? " [已判睡]" : ""));
            return OpResult.ok(outcome.verdict().text());
        }
    }

    // ------------------------------------------------------------ 对外

    /**
     * 广播 + 落盘。STATE 每轮都发；ASLEEP / AWAKE 只在状态边沿发一次。
     *
     * 注意：不要给 Intent 设 package，否则 Tasker / AutoJS 收不到。
     */
    public static final class Publish implements Task {

        @Override
        public String name() {
            return "广播与落盘";
        }

        @Override
        public OpResult run(TaskContext ctx) {
            Verdict verdict = ctx.verdict;
            if (verdict == null) {
                return OpResult.skip("没有判定结果可发布");
            }
            send(ctx, BC_STATE, verdict, true);
            if (ctx.transition == Transition.FELL_ASLEEP) {
                send(ctx, BC_ASLEEP, verdict, false);
                LogBus.log(">>> 判定入睡，已广播 ASLEEP");
            } else if (ctx.transition == Transition.WOKE_UP) {
                send(ctx, BC_AWAKE, verdict, false);
                LogBus.log(">>> 已清醒，广播 AWAKE");
            }
            EventStore.writeState(ctx.app, verdict, ctx.sleeping);
            EventStore.appendVerdict(ctx.app, verdict, ctx.sleeping);
            return OpResult.ok(verdict.text());
        }

        private void send(TaskContext ctx, String action, Verdict verdict, boolean withAsleep) {
            Intent intent = new Intent(action);
            intent.putExtra("bpm", verdict.median);
            intent.putExtra("score", verdict.score);
            if (withAsleep) {
                intent.putExtra("asleep", ctx.sleeping);
            }
            intent.putExtra("at", System.currentTimeMillis());
            try {
                ctx.app.sendBroadcast(intent);
            } catch (Throwable t) {
                LogBus.log("广播失败: " + t.getMessage());
            }
        }
    }

    /** 只在「本轮刚判睡」且用户开了自动锁屏时才执行。 */
    public static final class AutoLock implements Task {

        @Override
        public String name() {
            return "自动锁屏";
        }

        @Override
        public OpResult run(TaskContext ctx) {
            if (ctx.transition != Transition.FELL_ASLEEP) {
                return OpResult.skip("本轮无需锁屏");
            }
            if (!ctx.cfg.autoLock()) {
                LogBus.log("自动锁屏已关闭，只广播不锁屏");
                return OpResult.skip("自动锁屏已关闭");
            }
            boolean locked = LockHelper.lock(ctx.app);
            if (locked) {
                return OpResult.ok("已锁屏");
            }
            return OpResult.fail(OpResult.Code.ERROR, "锁屏未执行（检查设备管理器是否激活）");
        }
    }
}
