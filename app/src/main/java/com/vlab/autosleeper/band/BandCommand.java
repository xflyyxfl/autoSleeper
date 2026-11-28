package com.vlab.autosleeper.band;

import java.util.Locale;
import java.util.UUID;

/**
 * 协议常量。所有命令字面量只在这里出现一次。
 *
 * 真机固件 RH192HAV001836 实测结论：
 *   20 01  解锁，连接后必须先发，否则后面所有命令被静默丢弃
 *   18 01  开启心率监测（LED 亮，开始采样）
 *   18 02  关闭监测（不是 18 00，发 00 会被当未知指令丢掉）
 *   18 fa  拉取整张心率表
 *   03     电量查询，空等时当心跳用
 *
 * 本固件不支持协议文档里的 0x16 实时心率（写进去没有任何回应）。
 */
public final class BandCommand {

    public static final UUID SVC_MAIN = UUID.fromString("000060ff-0000-1000-8000-00805f9b34fb");
    public static final UUID CH_WRITE = UUID.fromString("00006001-0000-1000-8000-00805f9b34fb");
    public static final UUID CH_NOTIFY = UUID.fromString("00006002-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final byte[] UNLOCK = new byte[]{0x20, 0x01};
    public static final byte[] LED_ON = new byte[]{0x18, 0x01};
    public static final byte[] LED_OFF = new byte[]{0x18, 0x02};
    public static final byte[] FETCH_PULSE = new byte[]{0x18, (byte) 0xFA};
    public static final byte[] PING = new byte[]{0x03};

    private BandCommand() {
    }

    public static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 这些帧太频繁且没有诊断价值，不打日志。
     * 过滤规则以前写在 GuardService.newBand() 里，属于 BLE 细节泄漏到服务层。
     */
    public static boolean worthLogging(int cmd) {
        return cmd != 0x18 && cmd != 0x16 && cmd != 0x1C && cmd != 0x20
                && cmd != 0x09 && cmd != 0x03 && cmd != 0xE5;
    }
}
