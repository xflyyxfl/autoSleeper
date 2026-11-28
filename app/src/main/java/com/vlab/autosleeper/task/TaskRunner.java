package com.vlab.autosleeper.task;

import android.content.Context;
import android.os.PowerManager;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.band.AbortedException;
import com.vlab.autosleeper.band.BandException;

import java.util.List;

/**
 * 任务链执行器：把一串原子操作按顺序跑完，统一处理
 * 唤醒锁、取消、异常映射、以及「谁开的连接谁负责关」。
 *
 * 流程只需要给出任务列表，不再重复写 try/catch 和资源释放。
 */
public final class TaskRunner {

    private static final long WAKE_TIMEOUT_MS = 30L * 60L * 1000L;

    private TaskRunner() {
    }

    public static OpResult run(TaskContext ctx, List<? extends Task> tasks) {
        PowerManager.WakeLock wake = null;
        try {
            wake = acquireWake(ctx.app);
            OpResult last = OpResult.ok("完成");
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                if (ctx.cancelled() || Thread.currentThread().isInterrupted()) {
                    return OpResult.cancel("已取消");
                }
                OpResult result;
                try {
                    result = task.run(ctx);
                } catch (AbortedException e) {
                    LogBus.log("[" + task.name() + "] 已取消");
                    return OpResult.cancel(e.getMessage());
                } catch (BandException e) {
                    LogBus.log("[" + task.name() + "] " + e.getMessage());
                    return OpResult.from(e);
                } catch (Throwable t) {
                    LogBus.log("[" + task.name() + "] 异常: " + t);
                    return OpResult.fail(OpResult.Code.ERROR, String.valueOf(t.getMessage()));
                }
                if (result == null) {
                    continue;
                }
                last = result;
                if (!result.ok() && !result.skipped()) {
                    return result;
                }
            }
            return last;
        } finally {
            if (ctx.ownsSession && ctx.session != null) {
                ctx.session.close();
                ctx.session = null;
                ctx.ownsSession = false;
            }
            releaseWake(wake);
        }
    }

    private static PowerManager.WakeLock acquireWake(Context app) {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSleeper::task");
            wl.setReferenceCounted(false);
            wl.acquire(WAKE_TIMEOUT_MS);
            return wl;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void releaseWake(PowerManager.WakeLock wake) {
        if (wake == null) {
            return;
        }
        try {
            if (wake.isHeld()) {
                wake.release();
            }
        } catch (Throwable ignored) {
        }
    }
}
