package com.vlab.autosleeper.task;

/**
 * 原子操作。
 *
 * 单个任务就能单独调用（脚本、界面按钮、调试），也能被 {@link TaskRunner} 串成流程。
 * 约束：任务之间只通过 {@link TaskContext} 通信，不持有流程状态。
 */
public interface Task {

    /** 日志与错误提示里显示的名字。 */
    String name();

    /**
     * 执行一次。
     *
     * @throws com.vlab.autosleeper.band.BandException  BLE 层失败，由 Runner 统一转成 OpResult
     * @throws com.vlab.autosleeper.band.AbortedException 被取消
     */
    OpResult run(TaskContext ctx) throws Exception;
}
