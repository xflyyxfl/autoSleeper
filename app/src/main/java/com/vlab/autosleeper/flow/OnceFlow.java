package com.vlab.autosleeper.flow;

import android.content.Context;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.band.AbortedException;
import com.vlab.autosleeper.band.BandException;
import com.vlab.autosleeper.band.BandSession;
import com.vlab.autosleeper.core.JudgeEngine;
import com.vlab.autosleeper.data.Config;
import com.vlab.autosleeper.task.OpResult;
import com.vlab.autosleeper.task.Task;
import com.vlab.autosleeper.task.TaskContext;
import com.vlab.autosleeper.task.TaskRunner;
import com.vlab.autosleeper.task.Tasks;

import java.util.Arrays;
import java.util.Locale;

/**
 * 单次测量：点一下 → 开灯 → 按设置的亮灯时长跑 → 到点自动收尾；期间可随时手动结束。
 *
 * 两种收尾走同一条后续，这是关键：
 *   到时自动结束 → 关灯 → 拉曲线 → 判定 → 广播 → （按需）锁屏
 *   手动提前结束 → 关灯 → 拉曲线 → 判定 → 广播 → （按需）锁屏
 *
 * 以前这里只亮 20 秒，而固件要求一格内累计约 210 秒才落盘，
 * 所以「立即测一次」永远拿不到新数据，判定用的还是几十分钟前的窗口。
 */
public final class OnceFlow implements Runnable {

    private static final long CONNECT_TIMEOUT_MS = 15000L;

    private final Context app;
    private final Config cfg;
    private final FlowListener listener;

    public OnceFlow(Context app, Config cfg, FlowListener listener) {
        this.app = app;
        this.cfg = cfg;
        this.listener = listener;
    }

    @Override
    public void run() {
        final int monitorSec = cfg.monitorSec();
        JudgeEngine engine = new JudgeEngine(
                JudgeEngine.strategyFor(cfg.judgeKind(), cfg.threshold(), cfg.dropBpm(),
                        cfg.baseline(), cfg.threshold()),
                cfg.confirmRounds());

        TaskContext ctx = new TaskContext(app, cfg);
        ctx.engine = engine;
        long now = System.currentTimeMillis();
        ctx.ledOnAt = now;
        ctx.ledOffAt = now + monitorSec * 1000L;

        listener.onStatus("单次测量中，约 " + monitorSec + " 秒后自动收尾");
        LogBus.log(String.format(Locale.US,
                "单次测量：开灯 %d 秒，可随时点「结束」提前收尾", monitorSec));

        try {
            try (BandSession session = BandSession.open(app, cfg.addr(), CONNECT_TIMEOUT_MS)) {
                ctx.session = session;
                session.ledOn();
                LogBus.log("已开灯，开始计时");

                boolean early = false;
                try {
                    session.awaitUntil(ctx.ledOffAt);
                } catch (AbortedException e) {
                    // 清掉中断标志：这不是「整条流程取消」，只是「提前收尾」，后面还要拉数据
                    Thread.interrupted();
                    early = true;
                    LogBus.log("手动提前结束亮灯，直接进入拉取");
                }

                try {
                    session.ledOff();
                } catch (BandException ignored) {
                    // 灯可能已经因为别的原因关了，不影响后续拉取
                }
                LogBus.log("已关灯，开始读取曲线");

                listener.onStatus(early ? "已结束采样，读取心率…" : "读取心率…");
                OpResult result = TaskRunner.run(ctx, Arrays.<Task>asList(
                        new Tasks.FetchPulse(),
                        new Tasks.Judge(engine),
                        new Tasks.Publish(),
                        new Tasks.AutoLock()));

                LogBus.log("单次测量结果: " + result.code + " " + result.message);
                listener.onStatus("单次测量完成：" + result.message);
            }
        } catch (AbortedException e) {
            LogBus.log("单次测量已取消");
            listener.onStatus("单次测量已取消");
        } catch (BandException e) {
            LogBus.log("单次测量失败: " + e.getMessage());
            listener.onStatus("单次测量失败：" + e.getMessage());
        } catch (Throwable t) {
            LogBus.log("单次测量异常: " + t);
            listener.onStatus("单次测量异常");
        } finally {
            listener.onFlowEnd(FlowRuntime.ONCE);
        }
    }
}
