package com.vlab.autosleeper.core;

/**
 * 判定方式。用枚举代替以前的 int 0/1，源码里不再出现魔法数字。
 */
public enum JudgeKind {

    /** 简单阈值：心率低于 M 持续 N 格，最直观、好解释。默认。 */
    THRESHOLD("阈值模式"),
    /** 相对基线四票打分，敏感但需要先学到清醒基线。高级选项。 */
    BASELINE("基线模式");

    private final String label;

    JudgeKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static JudgeKind parse(String name) {
        if (BASELINE.name().equalsIgnoreCase(name)) {
            return BASELINE;
        }
        return THRESHOLD;
    }
}
