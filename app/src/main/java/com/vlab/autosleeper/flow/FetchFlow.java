package com.vlab.autosleeper.flow;

import android.content.Context;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.band.BandException;
import com.vlab.autosleeper.band.BandSession;
import com.vlab.autosleeper.data.Config;
import com.vlab.autosleeper.task.OpResult;
import com.vlab.autosleeper.task.Task;
import com.vlab.autosleeper.task.TaskContext;
import com.vlab.autosleeper.task.TaskRunner;
import com.vlab.autosleeper.task.Tasks;

import java.util.Collections;

/**
 * 仅拉取：连表 → 读曲线 → 更新界面。
 *
 * 严格只读：不开灯、不改心率监测开关、不判定、不锁屏。
 * 以前它复用了守护的拉取方法，会累加「连续几轮没新数据」的计数，
 * 点 3 次就会去动手环的监测开关，与「只读」的承诺矛盾。
 */
public final class FetchFlow implements Runnable {

    private static final long CONNECT_TIMEOUT_MS = 15000L;

    private final Context app;
    private final Config cfg;
    private final FlowListener listener;

    public FetchFlow(Context app, Config cfg, FlowListener listener) {
        this.app = app;
        this.cfg = cfg;
        this.listener = listener;
    }

    @Override
    public void run() {
        listener.onStatus("读取心率曲线…");
        LogBus.log("手动拉取：连接 " + cfg.addr());
        TaskContext ctx = new TaskContext(app, cfg);

        try {
            try (BandSession session = BandSession.open(app, cfg.addr(), CONNECT_TIMEOUT_MS)) {
                ctx.session = session;
                OpResult result = TaskRunner.run(ctx,
                        Collections.<Task>singletonList(new Tasks.FetchPulse()));
                LogBus.log("手动拉取结果: " + result.code + " " + result.message);
                listener.onStatus(result.ok() ? "曲线已更新" : "拉取失败：" + result.message);
            }
        } catch (BandException e) {
            LogBus.log("手动拉取失败: " + e.getMessage());
            listener.onStatus("拉取失败：" + e.getMessage());
        } catch (Throwable t) {
            LogBus.log("手动拉取异常: " + t);
            listener.onStatus("拉取异常");
        } finally {
            listener.onFlowEnd(FlowRuntime.FETCH);
        }
    }
}
