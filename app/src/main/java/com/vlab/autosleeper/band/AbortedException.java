package com.vlab.autosleeper.band;

/**
 * 流程被主动取消：用户点了「结束」，或者守护被停止。
 *
 * 单独一个类型，是为了让流程能精确区分「取消」和「失败」：
 * 单次测量的取消意味着「提前收尾，继续拉数据」，
 * 守护的取消意味着「立刻退出循环」。退出后不会再执行任何副作用（比如点灯）。
 */
public class AbortedException extends Exception {

    private static final long serialVersionUID = 1L;

    public AbortedException(String why) {
        super(why == null ? "已取消" : why);
    }
}
