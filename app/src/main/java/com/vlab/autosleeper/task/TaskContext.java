package com.vlab.autosleeper.task;

import android.content.Context;

import com.vlab.autosleeper.band.BandSession;
import com.vlab.autosleeper.core.JudgeEngine;
import com.vlab.autosleeper.core.PulseTable;
import com.vlab.autosleeper.core.Transition;
import com.vlab.autosleeper.core.Verdict;
import com.vlab.autosleeper.data.Config;

/**
 * 一条流程里各任务共享的状态载体。
 *
 * 这是「流程调用」的胶水：前一个任务把结果放进来，后一个任务拿去用，
 * 所以单次测量和守护循环能复用完全相同的任务序列，只是上下文不同。
 */
public final class TaskContext {

    public final Context app;
    public final Config cfg;

    /** 当前 BLE 会话；由流程开启，或由 Connect 任务开启。 */
    public BandSession session;
    /** 本会话是否由 Connect 任务开启（决定 TaskRunner 结束时要不要关）。 */
    public boolean ownsSession;

    public JudgeEngine engine;

    /** FetchPulse 的产物。 */
    public PulseTable table;
    /** Judge 的产物。 */
    public Verdict verdict;
    public Transition transition = Transition.NONE;
    public boolean sleeping;

    /** 本轮计划的开灯/关灯时刻。 */
    public long ledOnAt;
    public long ledOffAt;

    public volatile boolean cancelRequested;

    public TaskContext(Context app, Config cfg) {
        this.app = app.getApplicationContext();
        this.cfg = cfg;
    }

    public void cancel() {
        cancelRequested = true;
    }

    public boolean cancelled() {
        return cancelRequested;
    }
}
