package com.vlab.autosleeper.flow;

import android.content.Context;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.data.Config;

/**
 * 流程的唯一调度入口：谁在跑、能不能启动、怎么停。
 *
 * 关键修复：{@link #stopGuard()} 会 interrupt **并 join**，确保旧线程真的退出之后
 * 才允许启动新线程。以前 RESTART 只是 interrupt 就立刻 new Thread，
 * 结果两个采样线程并存，靠 BLE 锁串行化后会出现「旧线程开灯、新线程关灯」。
 */
public final class FlowRuntime {

    public static final String GUARD = "guard";
    public static final String ONCE = "once";
    public static final String FETCH = "fetch";

    private static final Object LOCK = new Object();
    /** 守护线程退出最多等多久。超时也要收回引用，避免永远卡住。 */
    private static final long STOP_JOIN_MS = 8000L;

    private static Thread guardThread;
    private static Thread onceThread;
    private static Thread fetchThread;

    private static volatile long lastTick = System.currentTimeMillis();

    private FlowRuntime() {
    }

    /** 采样线程最近一次活着的时刻，看门狗靠它判断是否卡死。 */
    public static long lastTick() {
        return lastTick;
    }

    public static void tick() {
        lastTick = System.currentTimeMillis();
    }

    public static boolean guardRunning() {
        Thread t;
        synchronized (LOCK) {
            t = guardThread;
        }
        return alive(t);
    }

    public static boolean onceRunning() {
        Thread t;
        synchronized (LOCK) {
            t = onceThread;
        }
        return alive(t);
    }

    public static boolean fetchRunning() {
        Thread t;
        synchronized (LOCK) {
            t = fetchThread;
        }
        return alive(t);
    }

    /** 除了 flow 自己之外还有别的流程在跑吗。 */
    public static boolean anyOtherRunning(String flow) {
        if (GUARD.equals(flow)) {
            return onceRunning() || fetchRunning();
        }
        if (ONCE.equals(flow)) {
            return guardRunning() || fetchRunning();
        }
        if (FETCH.equals(flow)) {
            return guardRunning() || onceRunning();
        }
        return guardRunning() || onceRunning() || fetchRunning();
    }

    // ------------------------------------------------------------ 守护

    public static boolean startGuard(Context app, Config cfg, FlowListener listener) {
        synchronized (LOCK) {
            if (alive(guardThread)) {
                LogBus.log("守护已在运行，忽略本次启动");
                return false;
            }
            Thread t = new Thread(new GuardFlow(app.getApplicationContext(), cfg, listener),
                    "sleep-guard");
            guardThread = t;
            t.start();
            return true;
        }
    }

    public static void stopGuard() {
        Thread t;
        synchronized (LOCK) {
            t = guardThread;
        }
        if (t == null) {
            return;
        }
        t.interrupt();
        join(t, STOP_JOIN_MS);
        synchronized (LOCK) {
            if (guardThread == t) {
                guardThread = null;
            }
        }
    }

    // ------------------------------------------------------------ 单次测量

    public static boolean startOnce(Context app, Config cfg, FlowListener listener) {
        synchronized (LOCK) {
            if (alive(guardThread)) {
                LogBus.log("守护运行中，单次测量请先停止守护");
                return false;
            }
            if (alive(onceThread)) {
                LogBus.log("上一次单次测量还没结束，忽略本次点击");
                return false;
            }
            Thread t = new Thread(new OnceFlow(app.getApplicationContext(), cfg, listener),
                    "sleep-once");
            onceThread = t;
            t.start();
            return true;
        }
    }

    /** 手动提前结束亮灯：只 interrupt，让流程自己走「提前收尾」分支继续拉数据。 */
    public static void stopOnce() {
        Thread t;
        synchronized (LOCK) {
            t = onceThread;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    // ------------------------------------------------------------ 仅拉取

    public static boolean startFetch(Context app, Config cfg, FlowListener listener) {
        synchronized (LOCK) {
            if (alive(fetchThread)) {
                LogBus.log("上一次拉取还没结束，忽略本次点击");
                return false;
            }
            Thread t = new Thread(new FetchFlow(app.getApplicationContext(), cfg, listener),
                    "sleep-fetch");
            fetchThread = t;
            t.start();
            return true;
        }
    }

    public static void stopFetch() {
        Thread t;
        synchronized (LOCK) {
            t = fetchThread;
        }
        if (t != null) {
            t.interrupt();
        }
    }

    public static void stopAll() {
        stopGuard();
        stopOnce();
        stopFetch();
    }

    /**
     * 只发中断，不等待。
     * 给 Service.onDestroy 用：那里在主线程上，不能 join 8 秒。
     */
    public static void interruptAll() {
        Thread g;
        Thread o;
        Thread f;
        synchronized (LOCK) {
            g = guardThread;
            o = onceThread;
            f = fetchThread;
        }
        if (g != null) {
            g.interrupt();
        }
        if (o != null) {
            o.interrupt();
        }
        if (f != null) {
            f.interrupt();
        }
    }

    // ------------------------------------------------------------ 内部

    private static boolean alive(Thread t) {
        return t != null && t.isAlive();
    }

    private static void join(Thread t, long timeoutMs) {
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
