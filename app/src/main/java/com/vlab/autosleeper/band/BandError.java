package com.vlab.autosleeper.band;

/**
 * BLE 失败原因。
 *
 * 以前 connect() 只返回 boolean，失败原因只写进日志字符串，
 * 调用方没法区分「该提示用户开蓝牙」还是「该跳过本轮」。现在每一种都有明确取值。
 */
public enum BandError {

    BT_OFF("蓝牙未开启"),
    BAD_ADDRESS("手环地址无效"),
    NO_SERVICE("未找到 600ff 服务"),
    NO_CHARACTERISTIC("命令/通知特征缺失"),
    TIMEOUT("连接超时"),
    NOT_AUTHED("命令通道未解锁"),
    DISCONNECTED("连接已断开"),
    WRITE_FAILED("指令写入失败"),
    BUSY("手环正被其它任务占用"),
    NO_DATA("没拿到心率曲线");

    private final String text;

    BandError(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
