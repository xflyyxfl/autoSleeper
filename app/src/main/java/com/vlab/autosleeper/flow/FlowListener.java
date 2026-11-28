package com.vlab.autosleeper.flow;

/**
 * 流程对外的回调。Service 只靠这三个回调更新通知、决定何时停掉自己，
 * 不需要知道流程内部在干什么。
 */
public interface FlowListener {

    /** 当前状态的一句话，用于前台通知。 */
    void onStatus(String text);

    /** 守护开始/结束。 */
    void onGuardChanged(boolean running);

    /** 某个流程彻底结束（flow 取 FlowRuntime.GUARD / ONCE / FETCH）。 */
    void onFlowEnd(String flow);
}
