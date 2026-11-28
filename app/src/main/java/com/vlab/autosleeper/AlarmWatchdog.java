package com.vlab.autosleeper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 定时看门狗：每 15 分钟检查守护服务是否还活着，不在就拉起来。
 *
 * Doze 下普通 setRepeating 会被冻结，这里用 setExactAndAllowWhileIdle
 * （权限不足时退回 setAndAllowWhileIdle），保证熄屏后仍能唤醒一次。
 */
public class AlarmWatchdog extends BroadcastReceiver {

    public static final String ACTION = "com.vlab.autosleeper.action.WATCHDOG";
    private static final long PERIOD = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        LogBus.log("看门狗检查");
        if (!GuardService.running) {
            LogBus.log("守护不在运行，看门狗重新拉起");
            try {
                Intent s = new Intent(c, GuardService.class);
                s.setAction(GuardService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    c.startForegroundService(s);
                } else {
                    c.startService(s);
                }
            } catch (Throwable t) {
                LogBus.log("看门狗拉起失败: " + t.getMessage());
            }
        }
        // 每次触发都排下一次，保证链不断
        scheduleNext(c);
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
