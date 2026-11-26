package com.vlab.autosleeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 前台守护服务：定时连手表 → 采心率 → 判定 → 广播结果。
 *
 * 广播（供 Tasker / AutoJS 消费）：
 *   com.vlab.autosleeper.ASLEEP   extra: bpm, score, at
 *   com.vlab.autosleeper.AWAKE    extra: bpm, score, at
 *   com.vlab.autosleeper.STATE    extra: bpm, score, asleep, at
 */
public class GuardService extends Service {

    public static final String ACTION_START = "com.vlab.autosleeper.action.START";
    public static final String ACTION_STOP = "com.vlab.autosleeper.action.STOP";
    public static final String ACTION_ONCE = "com.vlab.autosleeper.action.ONCE";

    public static final String BC_ASLEEP = "com.vlab.autosleeper.ASLEEP";
    public static final String BC_AWAKE = "com.vlab.autosleeper.AWAKE";
    public static final String BC_STATE = "com.vlab.autosleeper.STATE";

    private static final String CH_ID = "guard";
    private static final int NOTIF_ID = 1001;

    public static volatile boolean running = false;

    private AppConfig cfg;
    private SleepJudge judge;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean stopRequested = false;
    private volatile boolean notifiedAsleep = false;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        cfg = new AppConfig(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "睡眠守护", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台监测手环心率并判断入睡");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            LogBus.log("收到停止指令");
            stopGuard();
            return START_NOT_STICKY;
        }
        boolean once = ACTION_ONCE.equals(act);
        startForeground(NOTIF_ID, buildNotification(once ? "单次测量中…" : "守护中，等待下次检测"));
        if (running) {
            LogBus.log("守护已在运行，忽略本次启动");
            if (once) {
                LogBus.log("（单次测量请先停止守护）");
            }
            return START_STICKY;
        }
        startWorker(once);
        return once ? START_NOT_STICKY : START_STICKY;
    }

    private void startWorker(boolean once) {
        running = true;
        stopRequested = false;
        notifiedAsleep = false;
        judge = new SleepJudge(cfg.baseline(), cfg.dropBpm(), cfg.needWindows());
        if (cfg.baseline() > 0) {
            LogBus.log("恢复上次基线: " + Math.round(cfg.baseline()) + " bpm");
        }
        acquireWake();
        worker = new Thread(() -> loop(once), "sleep-guard");
        worker.start();
    }

    private void loop(boolean once) {
        LogBus.log(once ? "开始单次测量" : "开始守护，间隔 " + cfg.intervalMin() + " 分钟");
        try {
            do {
                long t0 = System.currentTimeMillis();
                try {
                    cycle();
                } catch (Throwable t) {
                    LogBus.log("周期异常: " + t);
                }
                if (once || stopRequested) {
                    break;
                }
                long wait = cfg.intervalMin() * 60000L - (System.currentTimeMillis() - t0);
                if (wait < 20000L) {
                    wait = 20000L;
                }
                LogBus.log(String.format(Locale.US, "下次检测在 %.1f 分钟后",
                        wait / 60000.0));
                updateNotification("守护中，下次检测 " + (wait / 60000) + " 分钟后");
                sleepQuiet(wait);
            } while (!stopRequested);
        } finally {
            running = false;
            releaseWake();
            try {
                stopForeground(true);
            } catch (Throwable ignored) {
            }
            stopSelf();
            LogBus.log("守护已结束");
        }
    }

    private void cycle() {
        final List<Integer> hr = Collections.synchronizedList(new ArrayList<Integer>());
        final long[] lastValid = {System.currentTimeMillis()};

        BandClient band = new BandClient(this, cfg.addr(), new BandClient.Listener() {
            @Override
            public void onHeartRate(int bpm) {
                hr.add(bpm);
                lastValid[0] = System.currentTimeMillis();
                updateNotification("测量中，当前 " + bpm + " bpm");
            }

            @Override
            public void onFrame(byte[] data) {
                // 心率帧太多，只记录异常帧
                if (data.length > 0 && (data[0] & 0xFF) != 0x16) {
                    LogBus.log("  <- " + hex(data));
                }
            }

            @Override
            public void onState(String state) {
            }
        });

        LogBus.log("连接 " + cfg.addr());
        if (!band.connect(15000)) {
            LogBus.log("本轮连接失败，跳过");
            band.close();
            return;
        }

        band.writeCommand(new byte[]{BandClient.CMD_HEART_RATE});
        LogBus.log("已下发 0x16，采样 " + cfg.sampleSec() + " 秒");

        long deadline = System.currentTimeMillis() + cfg.sampleSec() * 1000L;
        int pokes = 0;
        while (!stopRequested && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (System.currentTimeMillis() - lastValid[0] > 12000) {
                if (pokes >= 4) {
                    LogBus.log("多次重触发仍无数据，提前结束本轮");
                    break;
                }
                pokes++;
                band.writeCommand(new byte[]{BandClient.CMD_HEART_RATE});
                LogBus.log("断流，重触发 0x16 (" + pokes + "/4)");
                lastValid[0] = System.currentTimeMillis();
            }
        }
        band.close();

        SleepJudge.Result r = judge.feed(snapshot(hr));
        cfg.setBaseline(judge.getBaseline());

        LogBus.log(String.format(Locale.US,
                "本轮结果: 中位=%d sd=%.1f 得分=%d %s",
                r.median, r.sd, r.score, r.asleep ? "已入睡" : "清醒/不确定"));

        sendState(r);

        if (r.asleep && !notifiedAsleep) {
            notifiedAsleep = true;
            send(BC_ASLEEP, r);
            LogBus.log(">>> 判定入睡，已广播 ASLEEP");
            updateNotification("已判定入睡，等待唤醒");
        } else if (!r.asleep && notifiedAsleep && r.score < 30) {
            notifiedAsleep = false;
            send(BC_AWAKE, r);
            LogBus.log(">>> 已清醒，广播 AWAKE");
        }
    }

    private static List<Integer> snapshot(List<Integer> hr) {
        synchronized (hr) {
            return new ArrayList<>(hr);
        }
    }

    private void sendState(SleepJudge.Result r) {
        Intent i = new Intent(BC_STATE);
        i.putExtra("bpm", r.median);
        i.putExtra("score", r.score);
        i.putExtra("asleep", r.asleep);
        i.putExtra("at", System.currentTimeMillis());
        sendBroadcast(i);
        writeState(r);
    }

    private void send(String action, SleepJudge.Result r) {
        Intent i = new Intent(action);
        i.putExtra("bpm", r.median);
        i.putExtra("score", r.score);
        i.putExtra("at", System.currentTimeMillis());
        sendBroadcast(i);
    }

    private void writeState(SleepJudge.Result r) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "state.txt");
            String content = String.format(Locale.US, "%s,%d,%d,%d%n",
                    r.asleep ? "asleep" : "awake", r.median, r.score, System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);
        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("睡眠守护")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return;
            }
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSleeper::guard");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(15 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    private void sleepQuiet(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopRequested) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(left, 3000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void stopGuard() {
        stopRequested = true;
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public void onDestroy() {
        stopRequested = true;
        if (worker != null) {
            worker.interrupt();
        }
        running = false;
        releaseWake();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
