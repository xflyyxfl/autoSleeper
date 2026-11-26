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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Haylou Solar LS05 BLE 客户端。
 *
 * 协议要点（来自 eralpozcan/haylou-ls05，真机固件 RH192IBV003006 验证）：
 *   服务 000060ff / 写 00006001（write-without-response）/ 通知 00006002
 *   订阅成功后手表主动发 1c
 *   实时心率：写单字节 0x16，回 16 11 00 &lt;bpm&gt;，约 1 帧/秒，需佩戴
 *   测量中断时会出现 16 11（空帧）或 e5 00 ff 00（传感器无效）
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
                    releaseReady();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state("连接断开 (status=" + status + ")");
                releaseReady();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                state("服务发现失败 status=" + status);
                releaseReady();
                return;
            }
            BluetoothGattService svc = g.getService(SVC_MAIN);
            if (svc == null) {
                state("未找到 600ff 服务（可能不是该协议族的表）");
                releaseReady();
                return;
            }
            chWrite = svc.getCharacteristic(CH_WRITE);
            chNotify = svc.getCharacteristic(CH_NOTIFY);
            if (chWrite == null || chNotify == null) {
                state("命令/通知特征缺失");
                releaseReady();
                return;
            }
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
                    writeCommand(CMD_UNLOCK);
                }
            } catch (Throwable t) {
                state("开启通知异常: " + t.getMessage());
            }
            ready = true;
            state("通道就绪");
            releaseReady();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d != null && CCCD.equals(d.getCharacteristic().getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
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

    private void releaseReady() {
        ready = false;
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
