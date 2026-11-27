package com.vlab.autosleeper;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Haylou Solar LS05 BLE 客户端（真机固件 RH192HAV001836 实测）。
 *
 * 服务 000060ff / 写 00006001（write-without-response）/ 通知 00006002。
 * 订阅成功后手表主动发 1c 握手帧。
 *
 * 关键坑：连接后必须先写 20 01 解锁，手表回 20 04 01 才处理业务命令，
 * 否则后续命令被静默丢弃。
 *
 * 本固件不支持文档里的 0x16 实时心率（写进去没有任何回应）。
 * 但它支持「开启监测 + 事后拉历史」这套更省电的路径：
 *   18 01   开启心率监测（手环自己按 10 分钟一格聚合，写进当天固定的格子里）
 *   18 02   关闭监测（注意不是 18 00，发 00 会被静默丢弃）
 *   18 fa   拉取心率历史，回 1 个摘要帧 + 至多 8 个数据帧 + 1 个结束帧
 *
 * 实测规律（2026-11-27 真机）：
 *   1. 关闭监测不会清空已写入的历史格，只丢弃当前未结算的半格；
 *   2. 一格内只要有约 4 分钟以上的有效采样，且结算时刻（每 10 分钟整）监测是开着的，
 *      这一格就会被写入；只有 3 分钟则写不进去。
 *   3. 因此可以用「每 10 分钟只开 2~3 分钟、卡在格尾关闭」的方式把 LED 占空比降到 20% 左右。
 *
 * 历史帧格式：
 *   18 04 07 &lt;y m d&gt; &lt;hh&gt; &lt;mm&gt; &lt;?&gt; &lt;crc16&gt;   摘要，hh/mm 是采集时间
 *   18 07 &lt;y m d&gt; &lt;seq&gt; &lt;12 字节数据&gt;          数据包，seq = 2,4,6,8,10
 *   18 fd &lt;crc&gt;                                    结束
 * 5 包 × 12 字节 = 60 格，每格 10 分钟，即最近 10 小时的滚动曲线。
 * 0xFF 表示该格没有数据（未佩戴 / 未开监测 / 当前格还没写满）。
 */
public class BandClient {

    public interface Listener {
        void onHeartRate(int bpm);

        void onFrame(byte[] data);

        void onState(String state);
    }

    public static final UUID SVC_MAIN = UUID.fromString("000060ff-0000-1000-8000-00805f9b34fb");
    public static final UUID CH_WRITE = UUID.fromString("00006001-0000-1000-8000-00805f9b34fb");
    public static final UUID CH_NOTIFY = UUID.fromString("00006002-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final byte CMD_HEART_RATE = 0x16;
    /** 认证/解锁帧。连接后必须先发这一条，否则手表会静默丢弃后续所有业务命令。 */
    public static final byte[] CMD_UNLOCK = new byte[]{0x20, 0x01};
    /** 开启心率监测（手环开始整夜记录 10 分钟一格的心率）。 */
    public static final byte[] CMD_HR_ON = new byte[]{0x18, 0x01};
    /**
     * 关闭心率监测。payload 是 0x02 而不是 0x00：
     * 反编译 h1.&lt;clinit&gt; 得到 transient=[0x01]（ON）、implements=[0x02]（OFF），
     * 发 0x00 会被固件当未知指令静默丢弃，传感器不会停。
     */
    public static final byte[] CMD_HR_OFF = new byte[]{0x18, 0x02};
    /** 拉取心率历史。 */
    public static final byte[] CMD_FETCH_PULSE = new byte[]{0x18, (byte) 0xFA};

    /**
     * 一天的总格数：144 格 × 10 分钟 = 24 小时。
     * 下标是「当天 00:00 起算的绝对格号」，不是滑动窗口，
     * 所以傍晚以后包号会一直涨（实测 11:33 回 6 包、13:41 回 8 包）。
     * 写成 96 会让 16:00 以后的数据全部被丢，夜间正好踩中。
     */
    public static final int SLOT_COUNT = 144;
    /** 每格代表的分钟数。 */
    public static final int SLOT_MINUTES = 10;
    public static final int NO_DATA = -1;

    /** 一次拉取的结果。 */
    public static class PulseHistory {
        /** 长度 SLOT_COUNT，值 30..220，NO_DATA 表示空格。下标 0 最早、末尾最新。 */
        public int[] slots = new int[SLOT_COUNT];
        /** 手表报的采集时间（时、分），失败时 -1。 */
        public int hour = -1;
        public int minute = -1;
        /** 有效格数。 */
        public int valid;
        /** 最后一条有效值所在的下标，-1 表示全空。 */
        public int lastIndex = -1;

        public boolean ok() {
            return valid > 0;
        }

        /** 取最近 n 个有效值，按时间正序。 */
        public java.util.List<Integer> recent(int n) {
            java.util.List<Integer> out = new java.util.ArrayList<>();
            for (int i = lastIndex; i >= 0 && out.size() < n; i--) {
                if (slots[i] != NO_DATA) {
                    out.add(0, slots[i]);
                }
            }
            return out;
        }

        /** 最后一次有效采集距今多少分钟。用格号差算，不受当前时刻影响。 */
        public int ageMinutes() {
            if (lastIndex < 0) {
                return Integer.MAX_VALUE;
            }
            if (hour < 0 || minute < 0) {
                return 0;   // 没拿到手表时刻，只能当作新鲜
            }
            int cur = (hour * 60 + minute) / SLOT_MINUTES;
            int d = cur - lastIndex;
            if (d < 0) {
                d = 0;
            }
            return d * SLOT_MINUTES;
        }
    }

    private final Context ctx;
    private final String address;
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chWrite;
    private BluetoothGattCharacteristic chNotify;

    private CountDownLatch connectedLatch;
    private CountDownLatch readyLatch;
    private volatile boolean ready;
    private volatile boolean authSeen;

    /** 心率历史收集状态。 */
    private final byte[] rawSlots = new byte[SLOT_COUNT];
    private final Object pulseLock = new Object();
    private volatile CountDownLatch pulseLatch;
    private volatile int pulseHour = -1;
    private volatile int pulseMinute = -1;
    private volatile int pulsePackets;

    public BandClient(Context ctx, String address, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.address = address;
        this.listener = listener;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state("已连接，发现服务中");
                CountDownLatch l = connectedLatch;
                if (l != null) {
                    l.countDown();
                }
                try {
                    g.discoverServices();
                } catch (Throwable t) {
                    state("discoverServices 失败: " + t.getMessage());
                    failReady();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state("连接断开 (status=" + status + ")");
                failReady();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state("服务发现失败 status=" + status);
                failReady();
                return;
            }
            BluetoothGattService svc = g.getService(SVC_MAIN);
            if (svc == null) {
                state("未找到 600ff 服务（可能不是该协议族的表）");
                failReady();
                return;
            }
            chWrite = svc.getCharacteristic(CH_WRITE);
            chNotify = svc.getCharacteristic(CH_NOTIFY);
            if (chWrite == null || chNotify == null) {
                state("命令/通知特征缺失");
                failReady();
                return;
            }
            // 先把 ready 置位：下面选配 CCCD 时要发解锁帧，writeCommand 依赖 ready
            ready = true;
            try {
                g.setCharacteristicNotification(chNotify, true);
                BluetoothGattDescriptor d = chNotify.getDescriptor(CCCD);
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                } else {
                    // 没有 CCCD 描述符时没有回调，直接在这里解锁
                    state("无 CCCD，直接发解锁帧");
                    writeCommand(CMD_UNLOCK);
                }
            } catch (Throwable t) {
                state("开启通知异常: " + t.getMessage());
                writeCommand(CMD_UNLOCK);
            }
            state("通道就绪");
            signalReady();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // 这里要比较描述符自己的 UUID（2902），不是它所属特征的 UUID（6002）
            if (d != null && CCCD.equals(d.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                // 关键：通知开关生效后立刻发解锁帧，手表收到才开始处理业务命令
                writeCommand(CMD_UNLOCK);
                state("已解锁命令通道（20 01）");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v != null) {
                handle(v);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (value != null) {
                handle(value);
            }
        }
    };

    /** 连接失败：清掉 ready 并唤醒等待方。 */
    private void failReady() {
        ready = false;
        signalReady();
    }

    /** 只负责唤醒等待方，不改变 ready。成功后必须用这个，否则会把 ready 自己清掉。 */
    private void signalReady() {
        CountDownLatch l = readyLatch;
        if (l != null) {
            l.countDown();
        }
        CountDownLatch c = connectedLatch;
        if (c != null) {
            c.countDown();
        }
    }

    private void state(String s) {
        LogBus.log("[BLE] " + s);
        try {
            listener.onState(s);
        } catch (Throwable ignored) {
        }
    }

    private void handle(byte[] d) {
        if (d.length == 0) {
            return;
        }
        try {
            listener.onFrame(d);
        } catch (Throwable ignored) {
        }
        int cmd = d[0] & 0xFF;
        // 20 01 的回应是 20 04 <code>：1=已绑定  3=等待中  4=手表等待确认
        if (cmd == 0x20 && d.length >= 3 && d[1] == 0x04) {
            int code = d[2] & 0xFF;
            authSeen = true;
            state("认证返回码 " + code + (code == 1 ? "（通道已开放）" : "（未绑定，业务命令可能被丢弃）"));
            return;
        }
        if (cmd == 0x16 || cmd == 0xE5) {
            if (d.length >= 4 && d[2] == 0x00) {
                int bpm = d[3] & 0xFF;
                if (bpm >= 30 && bpm <= 220) {
                    try {
                        listener.onHeartRate(bpm);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return;
        }

        // 心率历史响应
        if (cmd == 0x18 && d.length >= 2) {
            int sub = d[1] & 0xFF;
            if (sub == 0x04) {
                // 摘要：18 04 07 y m d hh mm ? crc16
                if (d.length >= 8) {
                    pulseHour = d[6] & 0xFF;
                    pulseMinute = d[7] & 0xFF;
                }
            } else if (sub == 0x07) {
                // 数据包：18 07 y m d seq <12 字节>
                if (d.length >= 18) {
                    int seq = d[5] & 0xFF;
                    int idx = (seq / 2 - 1) * 12;
                    if (idx >= 0 && idx + 12 <= SLOT_COUNT) {
                        synchronized (pulseLock) {
                            System.arraycopy(d, 6, rawSlots, idx, 12);
                        }
                        pulsePackets++;
                    }
                }
            } else if (sub == 0xFD) {
                // 结束帧
                CountDownLatch l = pulseLatch;
                if (l != null) {
                    l.countDown();
                }
            }
        }
    }

    /** 阻塞连接，返回是否已就绪。 */
    public boolean connect(int timeoutMs) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            state("本机不支持蓝牙");
            return false;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            state("蓝牙未开启");
            return false;
        }
        BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(address);
        } catch (Throwable t) {
            state("地址无效: " + address);
            return false;
        }

        connectedLatch = new CountDownLatch(1);
        readyLatch = new CountDownLatch(1);
        ready = false;
        authSeen = false;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = dev.connectGatt(ctx, false, callback);
            }
        } catch (Throwable t) {
            state("connectGatt 异常: " + t.getMessage());
            return false;
        }
        if (gatt == null) {
            state("connectGatt 返回 null");
            return false;
        }

        try {
            if (!readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                state("连接或服务发现超时");
                close();
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            return false;
        }

        if (!ready) {
            close();
            return false;
        }

        // 确认解锁已被手表受理；没回应就重发，最多 3 次
        for (int i = 0; i < 3 && !authSeen; i++) {
            writeCommand(CMD_UNLOCK);
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!authSeen) {
            state("警告：未收到认证回应，心率可能读不到");
        }
        return true;
    }

    /** 发送一个字节或字节序列到命令通道。 */
    public boolean writeCommand(byte[] payload) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = chWrite;
        if (g == null || c == null || !ready || payload == null || payload.length == 0) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // 返回值 0 即 BluetoothStatusCodes.SUCCESS
                int rc = g.writeCharacteristic(c, payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                return rc == 0;
            }
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            c.setValue(payload);
            return g.writeCharacteristic(c);
        } catch (Throwable t) {
            state("写入异常: " + t.getMessage());
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 拉取心率历史曲线。阻塞直到收到结束帧或超时。
     * 正常一次往返约 1~3 秒，给 12 秒足够。
     */
    public PulseHistory fetchPulseHistory(long timeoutMs) {
        PulseHistory h = new PulseHistory();
        synchronized (pulseLock) {
            Arrays.fill(rawSlots, (byte) 0xFF);
        }
        pulseHour = -1;
        pulseMinute = -1;
        pulsePackets = 0;
        CountDownLatch latch = new CountDownLatch(1);
        pulseLatch = latch;

        if (!writeCommand(CMD_FETCH_PULSE)) {
            pulseLatch = null;
            state("拉取心率历史失败：命令通道未就绪");
            return h;
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                state("拉取心率历史超时（已收 " + pulsePackets + " 包）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pulseLatch = null;

        synchronized (pulseLock) {
            int valid = 0;
            for (int i = 0; i < SLOT_COUNT; i++) {
                int v = rawSlots[i] & 0xFF;
                if (v == 0xFF || v < 25 || v > 230) {
                    h.slots[i] = NO_DATA;
                } else {
                    h.slots[i] = v;
                    valid++;
                    h.lastIndex = i;
                }
            }
            h.valid = valid;
        }
        h.hour = pulseHour;
        h.minute = pulseMinute;
        return h;
    }

    /** 开启/关闭手环自身的心率监测。 */
    public boolean setHeartRateMonitor(boolean on) {
        return writeCommand(on ? CMD_HR_ON : CMD_HR_OFF);
    }

    public void close() {
        ready = false;
        authSeen = false;
        BluetoothGatt g = gatt;
        gatt = null;
        chWrite = null;
        chNotify = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (Throwable ignored) {
            }
            try {
                g.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
