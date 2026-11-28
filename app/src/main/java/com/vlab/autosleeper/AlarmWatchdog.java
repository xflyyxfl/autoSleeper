package com.vlab.autosleeper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.vlab.autosleeper.flow.FlowRuntime;

import java.util.Locale;

/**
 * 定时看门狗：每 5 分钟检查守护是否还活着，不在就拉起来；线程卡死就重启。
 *
 * Doze 下普通 setRepeating 会被冻结，这里用 setExactAndAllowWhileIdle
 * （权限不足时退回 setAndAllowWhileIdle），保证熄屏后仍能唤醒一次。
 *
 * 状态查询改走 {@link FlowRuntime}：睡眠状态和线程都由流程层统一持有，
 * 看门狗不再依赖 Service 里的静态字段。
 */
public class AlarmWatchdog extends BroadcastReceiver {

    public static final String ACTION = "com.vlab.autosleeper.action.WATCHDOG";
    private static final long PERIOD = 5 * 60 * 1000L;
    /** 采样线程超过 3 分钟没心跳就认定卡死。 */
    private static final long STUCK = 3 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        LogBus.log("看门狗检查");
        try {
            if (!FlowRuntime.guardRunning()) {
                LogBus.log("守护不在运行，看门狗重新拉起");
                start(c, GuardService.ACTION_START);
            } else {
                long tick = FlowRuntime.lastTick();
                long age = tick > 0 ? System.currentTimeMillis() - tick : 0L;
                if (age > STUCK) {
                    LogBus.log(String.format(Locale.US,
                            "采样线程 %d 秒无心跳，强制重启", age / 1000L));
                    start(c, GuardService.ACTION_RESTART);
                }
            }
        } catch (Throwable t) {
            LogBus.log("看门狗异常: " + t.getMessage());
        }
        // 每次触发都排下一次，保证链不断
        scheduleNext(c);
    }

    private static void start(Context c, String action) {
        Intent s = new Intent(c, GuardService.class);
        s.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(s);
        } else {
            c.startService(s);
        }
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, AlarmWatchdog.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, 771, i, flags);
    }

    public static void scheduleNext(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            long at = System.currentTimeMillis() + PERIOD;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi(c));
            }
        } catch (Throwable t) {
            LogBus.log("看门狗排程失败: " + t.getMessage());
        }
    }

    public static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi(c));
            }
        } catch (Throwable ignored) {
        }
    }
}
