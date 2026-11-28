package com.vlab.autosleeper.core;

/**
 * 判定原因的机器可读标识。
 *
 * 以前只有一句拼接出来的中文 note，界面、日志、广播各取所需，没法比较、没法测试。
 * 现在判定只产出枚举，人话由 {@link #label()} 统一给，展示层不再自己拼字符串。
 */
public enum Reason {

    /** 本轮没有足够样本。 */
    NOT_ENOUGH("样本不足"),
    /** 窗口里有空洞，不能断言「持续」。 */
    GAP("数据有缺口"),
    /** 刚开始建立基线，本轮不判定。 */
    BASELINE_INIT("建立基线"),
    /** 全部格都低于阈值。 */
    BELOW("低于阈值"),
    /** 中位数已经高于阈值。 */
    ABOVE("高于阈值"),
    /** 低于阈值但有明显波动，不算「持续低」。 */
    VOLATILE("波动过大"),
    /** 相对基线明显下降。 */
    DROP("低于基线"),
    /** 没有相对基线下降。 */
    NO_DROP("未低于基线"),
    /** 窗口最大值超过基线，说明有清醒活动。 */
    JUMP("有突升"),
    /** 低心率且极度平稳的兜底判定。 */
    LOW_FLAT("低心率平稳");

    private final String label;

    Reason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
