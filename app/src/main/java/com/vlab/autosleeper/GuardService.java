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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.vlab.autosleeper.data.Config;
import com.vlab.autosleeper.data.PulseRepository;
import com.vlab.autosleeper.flow.FlowListener;
import com.vlab.autosleeper.flow.FlowRuntime;
import com.vlab.autosleeper.task.OpResult;
import com.vlab.autosleeper.task.Task;
import com.vlab.autosleeper.task.TaskContext;
import com.vlab.autosleeper.task.TaskRunner;
import com.vlab.autosleeper.task.Tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 前台服务：只做三件事 —— 生命周期、前台通知、把 Intent 路由到流程。
 *
 * 以前这里 674 行，同时管线程、调度、BLE 编排、判定、落盘、广播、唤醒锁。
 * 现在业务全部下沉到 flow/task 层，服务本身只负责「处理谁的指令」和「什么时候停自己」。
 *
 * 对外契约（Tasker / AutoJS）保持不变：
 *   START / STOP / ONCE / FETCH / RESTART
 *   广播 ASLEEP / AWAKE / STATE，extra: bpm score asleep at
 *   state.txt 格式 asleep,62,80,1732...
 * 新增：
 *   STOP_ONCE  提前结束单次测量的亮灯（随后自动拉取、判定）
 *   TASK       单独执行一个原子操作，extra: task=connect|ledOn|ledOff|fetchPulse|judge
 */
public class GuardService extends Service implements FlowListener {

    public static final String ACTION_START = "com.vlab.autosleeper.action.START";
    public static final String ACTION_STOP = "com.vlab.autosleeper.action.STOP";
    public static final String ACTION_ONCE = "com.vlab.autosleeper.action.ONCE";
    /** 提前结束单次测量的亮灯，流程会继续拉数据并判定。 */
    public static final String ACTION_STOP_ONCE = "com.vlab.autosleeper.action.STOP_ONCE";
    public static final String ACTION_FETCH = "com.vlab.autosleeper.action.FETCH";
    /** 看门狗检测到采样线程卡死时发出的强制重启指令。 */
    public static final String ACTION_RESTART = "com.vlab.autosleeper.action.RESTART";
    /** 单独执行一个原子操作，供脚本组合流程。 */
    public static final String ACTION_TASK = "com.vlab.autosleeper.action.TASK";

    public static final String EXTRA_TASK = "task";

    public static final String TASK_CONNECT = "connect";
    public static final String TASK_LED_ON = "ledOn";
    public static final String TASK_LED_OFF = "ledOff";
    public static final String TASK_FETCH = "fetchPulse";
    public static final String TASK_JUDGE = "judge";

    private static final String CH_ID = "guard";
    private static final int NOTIF_ID = 1001;

    private Config cfg;
    private NotificationManager nm;
    private ExecutorService control;
    private volatile String statusText = "待机";

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        cfg = new Config(this);
        cfg.migrate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        control = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "guard-control");
                t.setDaemon(true);
                return t;
            }
        });
        // 进程刚起来内存是空的：把上次落盘的曲线读回来，界面不会白屏
        PulseRepository.restore(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            action = ACTION_START;
        }

        // 必须同步调用，否则 startForegroundService 之后 5 秒内没转前台会被系统干掉
        startForeground(NOTIF_ID, buildNotification(defaultText(action)));

        if (ACTION_STOP.equals(action)) {
            handleStop();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_ONCE.equals(action)) {
            LogBus.log("收到「提前结束」指令");
            submit(new Runnable() {
                @Override
                public void run() {
                    FlowRuntime.stopOnce();
                }
            });
            return START_NOT_STICKY;
        }
        if (ACTION_FETCH.equals(action)) {
            submit(new Runnable() {
                @Override
                public void run() {
                    if (!FlowRuntime.startFetch(GuardService.this, cfg, GuardService.this)) {
                        updateNotification("拉取未能启动");
                        maybeStopSelf(FlowRuntime.FETCH);
                    }
                }
            });
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            LogBus.log("看门狗：重启守护");
            submit(new Runnable() {
                @Override
                public void run() {
                    FlowRuntime.stopGuard();
                    FlowRuntime.startGuard(GuardService.this, cfg, GuardService.this);
                    AlarmWatchdog.scheduleNext(GuardService.this);
                }
            });
            return START_STICKY;
        }
        if (ACTION_ONCE.equals(action)) {
            submit(new Runnable() {
                @Override
                public void run() {
                    if (!FlowRuntime.startOnce(GuardService.this, cfg, GuardService.this)) {
                        updateNotification("单次测量未能启动（守护运行中请先停止）");
                        maybeStopSelf(FlowRuntime.ONCE);
                    }
                }
            });
            return START_NOT_STICKY;
        }
        if (ACTION_TASK.equals(action)) {
            final String task = intent == null ? null : intent.getStringExtra(EXTRA_TASK);
            submit(new Runnable() {
                @Override
                public void run() {
                    runSingleTask(task);
                }
            });
            return START_NOT_STICKY;
        }

        // 默认 = 开始守护
        submit(new Runnable() {
            @Override
            public void run() {
                if (FlowRuntime.startGuard(GuardService.this, cfg, GuardService.this)) {
                    AlarmWatchdog.scheduleNext(GuardService.this);
                } else {
                    updateNotification("守护已在运行");
                }
            }
        });
        return START_STICKY;
    }

    // ------------------------------------------------------------ 指令处理

    private void handleStop() {
        LogBus.log("收到停止指令");
        FloatWindow.hide();
        AlarmWatchdog.cancel(this);
        submit(new Runnable() {
            @Override
            public void run() {
                FlowRuntime.stopGuard();
                maybeStopSelf("stop");
            }
        });
    }

    /**
     * 单独跑一个原子操作。
     * 除 connect 外都会自动先建连接，TaskRunner 会在结束时把连接关掉。
     */
    private void runSingleTask(String name) {
        List<Task> chain = new ArrayList<>();
        TaskContext ctx = new TaskContext(this, cfg);

        if (TASK_CONNECT.equalsIgnoreCase(name)) {
            chain.add(new Tasks.Connect());
        } else if (TASK_LED_ON.equalsIgnoreCase(name)) {
            chain.add(new Tasks.Connect());
            chain.add(new Tasks.LedOn());
        } else if (TASK_LED_OFF.equalsIgnoreCase(name)) {
            chain.add(new Tasks.Connect());
            chain.add(new Tasks.LedOff());
        } else if (TASK_FETCH.equalsIgnoreCase(name)) {
            chain.add(new Tasks.Connect());
            chain.add(new Tasks.FetchPulse());
        } else if (TASK_JUDGE.equalsIgnoreCase(name)) {
            chain.add(new Tasks.Connect());
            chain.add(new Tasks.FetchPulse());
            chain.add(new Tasks.Judge());
        } else {
            LogBus.log("未知的 task: " + name);
            updateNotification("未知的 task: " + name);
            maybeStopSelf("task");
            return;
        }

        OpResult result = TaskRunner.run(ctx, chain);
        LogBus.log("单独执行 " + name + " -> " + result.code + " " + result.message);
        updateNotification(name + "：" + result.message);
        maybeStopSelf("task");
    }

    // ------------------------------------------------------------ FlowListener

    @Override
    public void onStatus(String text) {
        statusText = text;
        updateNotification(text);
    }

    @Override
    public void onGuardChanged(boolean running) {
        if (running) {
            updateNotification("守护中");
        }
    }

    @Override
    public void onFlowEnd(String flow) {
        if (FlowRuntime.anyOtherRunning(flow)) {
            updateNotification(statusText);
            return;
        }
        LogBus.log("所有流程已结束，停止服务");
        stopForegroundCompat();
        stopSelf();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onDestroy() {
        // 只发中断不等待：这里在主线程上，join 会卡住
        FlowRuntime.interruptAll();
        if (control != null) {
            control.shutdownNow();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void maybeStopSelf(String flow) {
        if (FlowRuntime.anyOtherRunning(flow)) {
            return;
        }
        stopForegroundCompat();
        stopSelf();
    }

    private void submit(Runnable r) {
        try {
            control.execute(r);
        } catch (Throwable t) {
            LogBus.log("控制线程不可用: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ 通知

    private String defaultText(String action) {
        if (ACTION_ONCE.equals(action)) {
            return "单次测量中…";
        }
        if (ACTION_FETCH.equals(action)) {
            return "读取心率曲线…";
        }
        if (ACTION_STOP.equals(action)) {
            return "正在停止…";
        }
        return "守护中，等待下次检测";
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

    private void stopForegroundCompat() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }
}
