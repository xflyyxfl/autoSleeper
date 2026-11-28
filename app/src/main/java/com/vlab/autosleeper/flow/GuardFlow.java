package com.vlab.autosleeper.flow;

import android.content.Context;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.band.AbortedException;
import com.vlab.autosleeper.band.BandException;
import com.vlab.autosleeper.band.BandSession;
import com.vlab.autosleeper.core.BoundaryClock;
import com.vlab.autosleeper.core.JudgeEngine;
import com.vlab.autosleeper.core.MeasureMode;
import com.vlab.autosleeper.core.SlotMath;
import com.vlab.autosleeper.data.Config;
import com.vlab.autosleeper.task.OpResult;
import com.vlab.autosleeper.task.Task;
import com.vlab.autosleeper.task.TaskContext;
import com.vlab.autosleeper.task.TaskRunner;
import com.vlab.autosleeper.task.Tasks;

import java.util.Arrays;
import java.util.Locale;

/**
 * 守护流程：等到开灯点 → 连表 → 开灯 → 跨整点关灯 → 拉曲线 → 判定 → 发布。
 *
 * 与单次测量的区别只在「时间怎么安排」，后面半段用的是同一批任务。
 *
 * 停止语义已修正：{@link #runCycle} 被 interrupt 时会抛 {@link AbortedException}，
 * 由 finally 负责关灯并关闭会话，绝不会在「已停止」之后还去点灯。
 */
public final class GuardFlow implements Runnable {

    private static final long CONNECT_TIMEOUT_MS = 15000L;
    /** 等待时每次最多睡多久，保证 interrupt 能被及时响应。 */
    private static final long SLEEP_SLICE_MS = 3000L;

    private final Context app;
    private final Config cfg;
    private final FlowListener listener;

    public GuardFlow(Context app, Config cfg, FlowListener listener) {
        this.app = app;
        this.cfg = cfg;
        this.listener = listener;
    }

    @Override
    public void run() {
        JudgeEngine engine = new JudgeEngine(
                JudgeEngine.strategyFor(cfg.judgeKind(), cfg.threshold(), cfg.dropBpm(),
                        cfg.baseline(), cfg.threshold()),
                cfg.confirmRounds());

        LogBus.log(String.format(Locale.US, "开始守护：每 %d 分钟一轮，每轮亮灯 %d 秒，判定=%s",
                cfg.intervalMin(), cfg.monitorSec(), cfg.judgeKind().label()));
        listener.onGuardChanged(true);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                FlowRuntime.tick();

                BoundaryClock.Plan plan = BoundaryClock.plan(System.currentTimeMillis(),
                        cfg.intervalMin(), cfg.monitorSec());
                long wait = plan.connectAt - System.currentTimeMillis();
                if (wait > 0) {
                    String when = SlotMath.clockLabel(plan.ledOnAt);
                    listener.onStatus("待机中，下次 " + when + " 开始采样");
                    LogBus.log(String.format(Locale.US, "待机 %.1f 分钟，%s 开始亮灯",
                            wait / 60000.0d, when));
                    sleepQuiet(wait);
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                // 睡醒后重新对表：进程可能被系统冻结过，醒来时这一格早就过去了
                String skip = BoundaryClock.skipReason(plan, System.currentTimeMillis());
                if (skip != null) {
                    LogBus.log("唤醒后" + skip + "，跳过本格（不连表，避免空转）");
                    continue;
                }

                runCycle(engine, plan);
            }
        } catch (AbortedException e) {
            LogBus.log("守护被取消");
        } catch (Throwable t) {
            LogBus.log("守护异常退出: " + t);
        } finally {
            listener.onGuardChanged(false);
            listener.onFlowEnd(FlowRuntime.GUARD);
            LogBus.log("守护已结束");
        }
    }

    /** 一轮：连表 → 开灯 → 跨整点关灯 → 拉取判定。 */
    private void runCycle(JudgeEngine engine, BoundaryClock.Plan plan) throws AbortedException {
        final boolean continuous = cfg.measureMode() == MeasureMode.CONTINUOUS;

        TaskContext ctx = new TaskContext(app, cfg);
        ctx.engine = engine;
        ctx.ledOnAt = plan.ledOnAt;
        ctx.ledOffAt = plan.ledOffAt;

        BandSession session = null;
        boolean ledOn = false;
        try {
            try {
                session = BandSession.open(app, cfg.addr(), CONNECT_TIMEOUT_MS);
                ctx.session = session;

                session.awaitUntil(plan.ledOnAt);
                session.ledOn();
                ledOn = true;
                listener.onStatus("采样中…");
                LogBus.log(continuous
                        ? "连续模式：心率灯保持开启，不关灯"
                        : String.format(Locale.US, "开灯，保持 %d 秒，整点后关闭",
                        plan.ledSeconds()));

                session.awaitUntil(plan.ledOffAt);
                if (!continuous) {
                    session.ledOff();
                    ledOn = false;
                }
                FlowRuntime.tick();

                listener.onStatus("读取心率曲线…");
                OpResult result = TaskRunner.run(ctx, Arrays.<Task>asList(
                        new Tasks.FetchPulse(),
                        new Tasks.Judge(engine),
                        new Tasks.Publish(),
                        new Tasks.AutoLock()));
                LogBus.log("本轮结果: " + result.code + " " + result.message);
            } catch (BandException e) {
                LogBus.log("本轮失败: " + e.getMessage());
            }
        } finally {
            boolean stopping = Thread.currentThread().isInterrupted();
            if (session != null) {
                // 只要灯还亮着且（已停止 或 不是连续模式），就负责关掉，避免整夜亮灯
                if (ledOn && (!continuous || stopping)) {
                    try {
                        session.ledOff();
                        LogBus.log("收尾：关灯");
                    } catch (Throwable ignored) {
                    }
                }
                session.close();
            }
            ctx.session = null;
            FlowRuntime.tick();
        }
    }

    private void sleepQuiet(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!Thread.currentThread().isInterrupted()) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            FlowRuntime.tick();
            try {
                Thread.sleep(Math.min(left, SLEEP_SLICE_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
