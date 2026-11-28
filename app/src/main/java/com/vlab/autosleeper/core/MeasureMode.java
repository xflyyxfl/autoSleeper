package com.vlab.autosleeper.core;

/**
 * 测量模式。
 *
 * PULSE：每轮只在整点前亮一小段，占空比约 25%，续航损失小一个量级（默认）。
 * CONTINUOUS：心率灯全程开启，曲线连续、判定最准，但耗电明显。
 */
public enum MeasureMode {

    PULSE("脉冲省电"),
    CONTINUOUS("连续监测");

    private final String label;

    MeasureMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static MeasureMode parse(String name) {
        if (CONTINUOUS.name().equalsIgnoreCase(name)) {
            return CONTINUOUS;
        }
        return PULSE;
    }
}
