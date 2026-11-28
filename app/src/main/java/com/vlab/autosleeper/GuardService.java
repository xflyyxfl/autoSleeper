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
    /** 看门狗检测到采样线程卡死时发出的强制重启指令。 */
    public static final String ACTION_RESTART = "com.vlab.autosleeper.action.RESTART";

    public static final String BC_ASLEEP = "com.vlab.autosleeper.ASLEEP";
    public static final String BC_AWAKE = "com.vlab.autosleeper.AWAKE";
    public static final String BC_STATE = "com.vlab.autosleeper.STATE";

    private static final String CH_ID = "guard";
    private static final int NOTIF_ID = 1001;

    public static volatile boolean running = false;
    /** 采样线程最近一次心跳的时刻；看门狗靠它判断线程是否卡死。 */
    public static volatile long lastWorkerTick = 0L;

    private AppConfig cfg;
    private SleepJudge judge;
    private ThresholdJudge simple;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean stopRequested = false;
    private volatile boolean notifiedAsleep = false;
    /** 连续多少轮拿到的曲线没有新数据。 */
    private int staleRounds = 0;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        cfg = new AppConfig(this);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        // 进程刚起来，内存是空的：先把上次落盘的曲线读回来，界面不会白屏
        PulseStore.load(this);
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
            FloatWindow.hide();
            AlarmWatchdog.cancel(this);
            stopGuard();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(act)) {
            LogBus.log("看门狗：采样线程无响应，强制重启采样");
            stopGuard();
            running = false;
            worker = null;
            startWorker(false);
            AlarmWatchdog.scheduleNext(this);
            if (cfg.floatEnabled()) {
                FloatWindow.show(this);
            }
            return START_STICKY;
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
        if (!once) {
            // 看门狗：即使进程被杀，Doze 下也能按点把守护拉回来
            AlarmWatchdog.scheduleNext(this);
        }
        // 单次测量几秒就结束，不挂悬浮球，避免服务退出时窗口状态残留
        if (cfg.floatEnabled() && !once) {
            FloatWindow.show(this);
        }
        return once ? START_NOT_STICKY : START_STICKY;
    }

    private void startWorker(boolean once) {
        running = true;
        stopRequested = false;
        notifiedAsleep = false;
        lastWorkerTick = System.currentTimeMillis();
        judge = new SleepJudge(cfg.baseline(), cfg.dropBpm(), cfg.needWindows());
        simple = new ThresholdJudge(cfg.threshold(), cfg.holdMinutes(), cfg.needRounds());
        if (cfg.baseline() > 0) {
            LogBus.log("恢复上次基线: " + Math.round(cfg.baseline()) + " bpm");
        }
        acquireWake();
        worker = new Thread(() -> loop(once), "sleep-guard");
        worker.start();
    }

    private void loop(boolean once) {
        LogBus.log(once ? "开始单次测量"
                : "开始守护：每 " + cfg.intervalMin() + " 分钟一次，每次亮灯 " + cfg.monitorSec() + " 秒");
        try {
            do {
                if (once) {
                    cycle(0L);
                    break;
                }
                long boundary = nextBoundary(cfg.intervalMin());
                // 保证开灯点还有足够余量：连接和握手本身要花几秒，
                // 如果开灯点已经过去，宁可等下一格，也不要连上去只亮 0 秒。
                long need = (cfg.monitorSec() + 25L) * 1000L;
                while (boundary - need < System.currentTimeMillis()) {
                    boundary += cfg.intervalMin() * 60000L;
                }
                long onAt = boundary - cfg.monitorSec() * 1000L;
                long wait = onAt - 50 * 1000L - System.currentTimeMillis();
                if (wait > 0) {
                    acquireWake();
                    String when = new java.text.SimpleDateFormat("HH:mm", Locale.US)
                            .format(new java.util.Date(onAt));
                    updateNotification("待机中，下次 " + when + " 开始采样");
                    LogBus.log(String.format(Locale.US, "待机 %.1f 分钟，%s 开始亮灯",
                            wait / 60000.0, when));
                    sleepQuiet(wait);
                }
                if (stopRequested) {
                    break;
                }

                // 睡醒后重新对表：进程可能被系统冻结过，醒来时这一格早就过去了。
                // 此时再连上去只会"开灯 0 秒"——既不产生数据，还会把刚开的灯立刻关掉。
                long late = System.currentTimeMillis() - onAt;
                if (late > 20000L) {
                    LogBus.log(String.format(Locale.US,
                            "唤醒时已错过开灯点 %d 秒，跳过本格（不连表，避免空转）",
                            late / 1000));
                    continue;
                }
                cycle(boundary);
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

    /** 下一个对齐到 intervalMin 整数倍的整点时刻（毫秒）。 */
    private static long nextBoundary(int intervalMin) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        long step = intervalMin * 60000L;
        long k = (System.currentTimeMillis() - dayStart) / step + 1;
        return dayStart + k * step;
    }

    /**
     * 一轮：连表 → 在整点前开通监测 → 跨过整点后关闭 → 拉曲线 → 判定。
     *
     * boundary 为 0 表示单次测量（不等整点，直接开 20 秒后拉）。
     */
    private void cycle(long boundary) {
        acquireWake();
        lastWorkerTick = System.currentTimeMillis();
        BandClient band = new BandClient(this, cfg.addr(), new BandClient.Listener() {
            @Override
            public void onHeartRate(int bpm) {
            }

            @Override
            public void onFrame(byte[] data) {
                // 0x18 曲线、0x09 活动心跳、0x1c 握手、0x20 认证都属正常流量
                if (data.length > 0) {
                    int c = data[0] & 0xFF;
                    if (c != 0x18 && c != 0x16 && c != 0x1C && c != 0x20 && c != 0x09) {
                        LogBus.log("  <- " + hex(data));
                    }
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

        boolean continuous = cfg.measureMode() == 1;

        long onAt;
        long offAt;
        if (boundary > 0) {
            // 连续模式立刻开灯并一直保持；脉冲模式只在整点前亮一小段
            onAt = continuous ? System.currentTimeMillis()
                    : boundary - cfg.monitorSec() * 1000L;
            offAt = boundary + 15000L;   // 必须跨过整点，否则这一格不结算
        } else {
            onAt = System.currentTimeMillis();
            offAt = onAt + 20000L;
        }

        sleepUntil(band, onAt);
        band.setHeartRateMonitor(true);

        if (continuous) {
            LogBus.log("连续模式：心率灯全程开启");
        } else {
            LogBus.log(String.format(Locale.US, "开灯，保持 %d 秒，整点后关闭",
                    Math.max(0, (offAt - System.currentTimeMillis()) / 1000)));
        }

        updateNotification("采样中…");
        sleepUntil(band, offAt);

        if (!continuous) {
            band.setHeartRateMonitor(false);
            LogBus.log("关灯（18 02）");
            sleepMs(500);
        }

        updateNotification("读取心率曲线…");
        BandClient.PulseHistory h = band.fetchPulseHistory(12000);

        // 连着几轮都拿不到新格子时，把手环的监测开关重置一次。
        // 固件偶尔会卡在"关了就不再开"的状态，重发一遍 18 02/18 01 能救回来。
        if (h.ok() && h.ageMinutes() > 35) {
            staleRounds++;
            if (staleRounds >= 3) {
                LogBus.log("连续 " + staleRounds + " 轮没有新数据，重置心率监测开关");
                band.setHeartRateMonitor(false);
                sleepMs(300);
                band.setHeartRateMonitor(true);
                sleepMs(800);
                band.setHeartRateMonitor(false);
                staleRounds = 0;
            }
        } else if (h.ok()) {
            staleRounds = 0;
        }
        band.close();

        if (!h.ok()) {
            LogBus.log("没拿到曲线：手环可能没戴，或本格没积累到足够采样");
            return;
        }

        int age = h.ageMinutes();
        int lastMin = h.lastIndex * BandClient.SLOT_MINUTES;
        LogBus.log(String.format(Locale.US,
                "曲线 %d 格有效，最后一格是 %02d:%02d（%d 分钟前）",
                h.valid, (lastMin / 60) % 24, lastMin % 60, age));

        List<Integer> all = h.recent(BandClient.SLOT_COUNT);
        LogBus.log("  最近 12 格: " + tailText(all, 12));

        // 先落盘再判新旧，界面上才能看到曲线
        PulseStore.update(h.slots, h.valid, h.lastIndex, h.cur, h.hour, h.minute);
        PulseStore.save(this);

        if (age > 35) {
            LogBus.log(String.format(Locale.US,
                    "本格没写进去：这次只亮了 %d 秒。固件要求连续采样约 210 秒以上才落格，"
                            + "先把「亮灯时长」调大到 240 秒以上再看",
                    cfg.monitorSec()));
            return;
        }

        SleepJudge.Result r;
        if (cfg.judgeMode() == 0) {
            // 简单阈值：心率低于 M 持续 N 分钟。
            // 按格号取，保证空档也进窗口（有空格就不能算"持续"）
            int need = Math.max(1, cfg.holdMinutes() / BandClient.SLOT_MINUTES);
            r = simple.feed(slotsWindow(h.slots, h.cur, need));
        } else {
            r = judge.feed(SleepJudge.window(all, cfg.windowSlots()));
            cfg.setBaseline(judge.getBaseline());
        }
        PulseStore.setVerdict(r.median, cfg.threshold(), r.asleep, r.note);
        PulseStore.appendVerdict(this, r.median, cfg.threshold(), r.asleep, r.note);
        // 必须放在 setVerdict 之后：悬浮球显示的就是 median，提前刷新会画成上一轮的值
        FloatWindow.refresh();

        LogBus.log(String.format(Locale.US,
                "本轮结果: 中位=%d 阈值=%d 得分=%d %s",
                r.median, cfg.threshold(), r.score, r.asleep ? "已入睡" : "清醒/不确定"));

        sendState(r);

        if (r.asleep && !notifiedAsleep) {
            notifiedAsleep = true;
            send(BC_ASLEEP, r);
            LogBus.log(">>> 判定入睡，已广播 ASLEEP");
            if (cfg.autoLock()) {
                updateNotification("已判定入睡，正在锁屏");
                boolean ok = LockHelper.lock(this);
                LogBus.log(ok ? ">>> 已锁屏" : ">>> 锁屏未执行（检查设备管理器是否激活）");
            } else {
                LogBus.log("自动锁屏已关闭，只广播不锁屏");
            }
            updateNotification("已判定入睡，等待唤醒");
        } else if (!r.asleep && notifiedAsleep) {
            notifiedAsleep = false;
            send(BC_AWAKE, r);
            LogBus.log(">>> 已清醒，广播 AWAKE");
        }
    }

    /**
     * 等到目标时刻。期间每 40 秒发一次电量查询当心跳，
     * 防止空闲太久被手环主动断开（实测空闲 11 分钟必断）。
     */
    private void sleepUntil(BandClient band, long targetMs) {
        while (!stopRequested) {
            long left = targetMs - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            lastWorkerTick = System.currentTimeMillis();
            if (!band.isConnected()) {
                // 连接已经掉了还接着等，只会白等一整轮（实测被冻结后会这样）
                LogBus.log("等待中连接已断开，提前结束本轮");
                return;
            }
            if (left > 45000L) {
                sleepMs(40000L);
                lastWorkerTick = System.currentTimeMillis();
                band.writeCommand(new byte[]{0x03});
                lastWorkerTick = System.currentTimeMillis();
            } else {
                sleepMs(left);
            }
        }
    }

    /** 以 cur 为基准取最近 n 格，空格（-1）保留在窗口里，避免把两段不连续的数据拼成"持续"。 */
    private static List<Integer> slotsWindow(int[] slots, int cur, int n) {
        List<Integer> out = new ArrayList<>();
        if (slots == null || cur < 0) {
            return out;
        }
        for (int k = n - 1; k >= 0; k--) {
            int i = BandClient.PulseHistory.slotAt(cur, k);
            if (i >= 0 && i < slots.length) {
                out.add(slots[i]);
            }
        }
        return out;
    }

    private static String tailText(List<Integer> v, int n) {        int from = Math.max(0, v.size() - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < v.size(); i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(v.get(i));
        }
        return sb.toString();
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSleeper::guard");
                wakeLock.setReferenceCounted(false);
            }
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            // 每个周期重新计时；等待循环里还会持续续期，防止长间隔被 Doze 打断
            wakeLock.acquire(30 * 60 * 1000L);
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
            // 等待期间持续续期，避免长间隔下 WakeLock 到期被 Doze 掐掉
            keepWake();
            try {
                Thread.sleep(Math.min(left, 3000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** WakeLock 续期。间隔大于 15 分钟时，光靠 acquireWake 一次是撑不到下一轮的。 */
    private void keepWake() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(30 * 60 * 1000L);
            }
        } catch (Throwable ignored) {
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
