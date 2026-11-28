package com.vlab.autosleeper.band;

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

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.core.BoundaryClock;
import com.vlab.autosleeper.core.PulseTable;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一次 BLE 会话。全工程唯一会碰 BluetoothGatt 的类。
 *
 * 设计要点（都是为了修掉以前的坑）：
 *
 * 1. 连接状态用 {@link #connected} 维护，由 onConnectionStateChange 真实更新。
 *    以前用 getDevice() != null 判断，这个与连接状态无关，导致「断连就提前结束本轮」
 *    的保护从来没生效过。
 *
 * 2. {@link #awaitUntil} 正常返回才代表「到点了」；被中断就抛 {@link AbortedException}。
 *    以前 sleepUntil 因为停止而退出后，调用方还会继续执行点灯，手环 LED 就一直亮着。
 *
 * 3. 互斥锁在 open() 内部，拿不到直接抛 BUSY，调用方不用写 tryLock/finally unlock。
 *
 * 4. 失败一律抛 {@link BandException} 并带原因，调用方能给用户不同的提示。
 */
public final class BandSession implements AutoCloseable {

    /** BLE 是单连接：守护、单次测量、手动拉取必须互斥。 */
    private static final ReentrantLock BLE_LOCK = new ReentrantLock();

    private static final long AUTH_RETRY_MS = 600L;
    private static final int AUTH_RETRY_TIMES = 3;
    /** 剩余等待超过这个时长才值得发心跳，否则不如直接睡到点。 */
    private static final long WAIT_PING_THRESHOLD_MS = 45_000L;
    /**
     * 关灯后到拉取之间留一点时间：固件需要把刚结算的半格写盘，
     * 立刻发 18 fa 偶尔会拿到还没更新的表。
     */
    private static final long FETCH_SETTLE_MS = 400L;

    private final Context ctx;
    private final String address;
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic chWrite;
    private volatile BluetoothGattCharacteristic chNotify;

    private volatile boolean ready;
    private volatile boolean connected;
    private volatile boolean authSeen;
    private volatile BandError failError;

    private volatile PulseCodec codec;
    private volatile CountDownLatch codecLatch;

    /** 同一条通知可能被新旧两个回调各投递一次，这里做 30ms 去重。 */
    private volatile byte[] lastFrame;
    private volatile long lastFrameAt;

    private boolean locked;

    private BandSession(Context ctx, String address) {
        this.ctx = ctx.getApplicationContext();
        this.address = address;
    }

    /**
     * 建立会话。调用方必须用 try-with-resources 或 finally 关闭。
     *
     * @throws BandException 无法建立可用会话，原因见 {@link BandException#error()}
     */
    public static BandSession open(Context ctx, String address, long timeoutMs) throws BandException {
        if (!BLE_LOCK.tryLock()) {
            throw new BandException(BandError.BUSY);
        }
        BandSession session = new BandSession(ctx, address);
        session.locked = true;
        try {
            session.connect(timeoutMs);
            return session;
        } catch (BandException e) {
            session.close();
            throw e;
        } catch (Throwable t) {
            session.close();
            throw new BandException(BandError.TIMEOUT, String.valueOf(t.getMessage()));
        }
    }

    public String address() {
        return address;
    }

    /** 连接是否真的还活着（进程被冻结后 GATT 往往已经悄悄断开）。 */
    public boolean alive() {
        return connected && ready;
    }

    public boolean authenticated() {
        return authSeen;
    }

    // ---------------------------------------------------------------- 动作

    public void ledOn() throws BandException {
        write(BandCommand.LED_ON);
        LogBus.log("[BLE] 开灯（18 01）");
    }

    public void ledOff() throws BandException {
        write(BandCommand.LED_OFF);
        LogBus.log("[BLE] 关灯（18 02）");
    }

    /**
     * 阻塞等待到目标时刻，期间每 40 秒发一次电量查询当心跳（空闲 11 分钟必断）。
     *
     * @throws AbortedException 等待期间被取消
     * @throws BandException    连接掉了
     */
    public void awaitUntil(long targetMs) throws BandException, AbortedException {
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AbortedException("等待被取消");
            }
            long left = targetMs - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            if (!alive()) {
                throw new BandException(BandError.DISCONNECTED, "等待中连接已断开");
            }
            if (left > WAIT_PING_THRESHOLD_MS) {
                sleep(BoundaryClock.PING_MS);
                if (!alive()) {
                    throw new BandException(BandError.DISCONNECTED, "等待中连接已断开");
                }
                writeQuiet(BandCommand.PING);
            } else {
                sleep(left);
            }
        }
    }

    /**
     * 拉取整张心率表。阻塞直到收到结束帧或超时。
     *
     * @throws BandException NO_DATA 表示连上了但一格数据都没有
     */
    public PulseTable fetchPulse(long timeoutMs) throws BandException, AbortedException {
        if (!alive()) {
            throw new BandException(BandError.DISCONNECTED);
        }
        PulseCodec c = new PulseCodec();
        CountDownLatch latch = new CountDownLatch(1);
        codec = c;
        codecLatch = latch;
        try {
            // 关灯后先让固件把格子写盘，再发拉取命令
            sleepQuiet(FETCH_SETTLE_MS);
            if (Thread.currentThread().isInterrupted()) {
                throw new AbortedException("拉取被取消");
            }
            write(BandCommand.FETCH_PULSE);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new AbortedException("拉取被取消");
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    LogBus.log("[BLE] 拉取超时，已收 " + c.packets() + " 包");
                    break;
                }
                if (latch.await(Math.min(left, 300L), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbortedException("拉取被中断");
        } finally {
            codec = null;
            codecLatch = null;
        }

        PulseTable table = c.build(System.currentTimeMillis());
        if (table.isEmpty()) {
            throw new BandException(BandError.NO_DATA, "已收 " + c.packets() + " 包");
        }
        return table;
    }

    @Override
    public void close() {
        ready = false;
        connected = false;
        authSeen = false;
        codec = null;
        codecLatch = null;

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
        if (locked) {
            locked = false;
            try {
                BLE_LOCK.unlock();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private void connect(long timeoutMs) throws BandException {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            throw new BandException(BandError.BT_OFF, "本机不支持蓝牙");
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            throw new BandException(BandError.BT_OFF);
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (Throwable t) {
            throw new BandException(BandError.BAD_ADDRESS, address);
        }

        BluetoothGatt g;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                g = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                g = device.connectGatt(ctx, false, callback);
            }
        } catch (Throwable t) {
            throw new BandException(BandError.TIMEOUT, "connectGatt 异常");
        }
        if (g == null) {
            throw new BandException(BandError.TIMEOUT, "connectGatt 返回 null");
        }
        gatt = g;

        boolean done;
        try {
            done = readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BandException(BandError.TIMEOUT, "等待连接被中断");
        }
        if (!done) {
            throw new BandException(BandError.TIMEOUT);
        }
        if (!ready) {
            throw new BandException(failError == null ? BandError.DISCONNECTED : failError);
        }

        // 确认解锁已被手表受理；没回应就重发
        for (int i = 0; i < AUTH_RETRY_TIMES && !authSeen; i++) {
            writeQuiet(BandCommand.UNLOCK);
            sleepQuiet(AUTH_RETRY_MS);
        }
        if (!authSeen) {
            LogBus.log("[BLE] 未收到认证回应，业务命令可能被丢弃");
        }
    }

    private boolean writeQuiet(byte[] payload) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = chWrite;
        if (g == null || c == null || !ready || payload == null || payload.length == 0) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int rc = g.writeCharacteristic(c, payload,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                return rc == 0;
            }
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            c.setValue(payload);
            return g.writeCharacteristic(c);
        } catch (Throwable t) {
            LogBus.log("[BLE] 写入异常: " + t.getMessage());
            return false;
        }
    }

    private void write(byte[] payload) throws BandException {
        if (!writeQuiet(payload)) {
            if (!alive()) {
                throw new BandException(BandError.DISCONNECTED);
            }
            throw new BandException(BandError.WRITE_FAILED, BandCommand.hex(payload));
        }
    }

    private void handle(byte[] frame) {
        if (frame == null || frame.length == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        byte[] prev = lastFrame;
        if (prev != null && now - lastFrameAt < 30L && Arrays.equals(prev, frame)) {
            return;
        }
        lastFrame = frame.clone();
        lastFrameAt = now;

        int cmd = frame[0] & 0xFF;

        // 20 01 的回应是 20 04 <code>
        if (cmd == 0x20 && frame.length >= 3 && frame[1] == 0x04) {
            authSeen = true;
            LogBus.log("[BLE] 认证返回码 " + (frame[2] & 0xFF));
            return;
        }

        if (BandCommand.worthLogging(cmd)) {
            LogBus.log("  <- " + BandCommand.hex(frame));
        }

        PulseCodec c = codec;
        if (c != null && c.onFrame(frame)) {
            CountDownLatch l = codecLatch;
            if (l != null) {
                l.countDown();
            }
        }
    }

    private void fail(BandError error, String detail) {
        if (failError == null) {
            failError = error;
        }
        ready = false;
        LogBus.log("[BLE] " + error.text() + (detail == null || detail.isEmpty() ? "" : "：" + detail));
        readyLatch.countDown();
        CountDownLatch l = codecLatch;
        if (l != null) {
            l.countDown();
        }
    }

    private void sleep(long ms) throws AbortedException {
        try {
            Thread.sleep(Math.max(1L, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AbortedException("等待被中断");
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                LogBus.log("[BLE] 已连接，发现服务中");
                try {
                    g.discoverServices();
                } catch (Throwable t) {
                    fail(BandError.NO_SERVICE, "discoverServices 失败");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                ready = false;
                LogBus.log("[BLE] 连接断开 (status=" + status + ")");
                if (failError == null) {
                    failError = BandError.DISCONNECTED;
                }
                readyLatch.countDown();
                CountDownLatch l = codecLatch;
                if (l != null) {
                    l.countDown();
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(BandError.NO_SERVICE, "status=" + status);
                return;
            }
            BluetoothGattService svc = g.getService(BandCommand.SVC_MAIN);
            if (svc == null) {
                fail(BandError.NO_SERVICE);
                return;
            }
            BluetoothGattCharacteristic w = svc.getCharacteristic(BandCommand.CH_WRITE);
            BluetoothGattCharacteristic n = svc.getCharacteristic(BandCommand.CH_NOTIFY);
            if (w == null || n == null) {
                fail(BandError.NO_CHARACTERISTIC);
                return;
            }
            chWrite = w;
            chNotify = n;
            // 先置位：下面选配 CCCD 时要发解锁帧，writeQuiet 依赖 ready
            ready = true;
            try {
                g.setCharacteristicNotification(n, true);
                BluetoothGattDescriptor d = n.getDescriptor(BandCommand.CCCD);
                if (d != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                    }
                } else {
                    LogBus.log("[BLE] 无 CCCD，直接发解锁帧");
                    writeQuiet(BandCommand.UNLOCK);
                }
            } catch (Throwable t) {
                LogBus.log("[BLE] 开启通知异常: " + t.getMessage());
                writeQuiet(BandCommand.UNLOCK);
            }
            LogBus.log("[BLE] 通道就绪");
            readyLatch.countDown();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            // 比较描述符自己的 UUID（2902），不是它所属特征的 UUID（6002）
            if (d != null && BandCommand.CCCD.equals(d.getUuid())
                    && status == BluetoothGatt.GATT_SUCCESS) {
                writeQuiet(BandCommand.UNLOCK);
                LogBus.log("[BLE] 已解锁命令通道（20 01）");
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
}
