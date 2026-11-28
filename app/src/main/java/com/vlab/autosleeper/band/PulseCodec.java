package com.vlab.autosleeper.band;

import com.vlab.autosleeper.core.PulseTable;
import com.vlab.autosleeper.core.SlotMath;

import java.util.Arrays;

/**
 * 心率历史帧解析。协议细节只在这里出现，其它层只拿到 {@link PulseTable}。
 *
 * 帧格式（真机实测）：
 *   18 04 07 &lt;y m d&gt; &lt;hh&gt; &lt;mm&gt; &lt;?&gt; &lt;crc16&gt;   摘要，hh/mm 是手表当前时间
 *   18 07 &lt;y m d&gt; &lt;seq&gt; &lt;12 字节数据&gt;          数据包，seq = 2,4,6…
 *   18 fd &lt;crc&gt;                                    结束
 *
 * 段号的关键坑：seq 的低半是段号，段 1..11 对应 00:00 起的每 2 小时，
 * 段 0 是跨天的那一段（22:00-24:00），固件把它排在最后发送。
 * 早期用 (seq/2-1)*12 会把段 0 算成 -12 丢掉，导致 22 点之后的数据全部收不到。
 */
public final class PulseCodec {

    private final byte[] raw;
    private int hour = -1;
    private int minute = -1;
    private int packets;

    public PulseCodec() {
        raw = new byte[SlotMath.SLOT_COUNT];
        Arrays.fill(raw, (byte) 0xFF);
    }

    public int packets() {
        return packets;
    }

    public int hour() {
        return hour;
    }

    public int minute() {
        return minute;
    }

    /**
     * 吃掉一帧。
     *
     * @return true 表示这是结束帧，可以收尾了
     */
    public boolean onFrame(byte[] frame) {
        if (frame == null || frame.length < 2) {
            return false;
        }
        if ((frame[0] & 0xFF) != 0x18) {
            return false;
        }
        int sub = frame[1] & 0xFF;

        if (sub == 0x04) {
            if (frame.length >= 8) {
                hour = frame[6] & 0xFF;
                minute = frame[7] & 0xFF;
            }
            return false;
        }

        if (sub == 0x07) {
            if (frame.length >= 18) {
                int seq = frame[5] & 0xFF;
                int seg = seq / 2;
                int idx = (seg == 0)
                        ? (SlotMath.SLOT_COUNT - 12)
                        : (seg - 1) * 12;
                if (idx >= 0 && idx + 12 <= SlotMath.SLOT_COUNT) {
                    System.arraycopy(frame, 6, raw, idx, 12);
                    packets++;
                }
            }
            return false;
        }

        return sub == 0xFD;
    }

    /** 收完后转成不可变表。0xFF 与越界值都会变成 NO_DATA。 */
    public PulseTable build(long nowMs) {
        int[] slots = SlotMath.emptySlots();
        for (int i = 0; i < SlotMath.SLOT_COUNT; i++) {
            int v = raw[i] & 0xFF;
            if (SlotMath.valid(v)) {
                slots[i] = v;
            }
        }
        return PulseTable.of(slots, hour, minute, nowMs);
    }
}
