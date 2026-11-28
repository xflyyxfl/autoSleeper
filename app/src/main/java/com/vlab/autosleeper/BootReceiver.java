package com.vlab.autosleeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.vlab.autosleeper.data.Config;

/**
 * 开机自启。守护靠 START_STICKY 能被系统拉回来，但重启手机后不会自动跑，
 * 这里补上：只要用户上次是开着守护的，开机后自动恢复。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Config cfg = new Config(c);
        if (!cfg.resumeOnBoot()) {
            return;
        }
        LogBus.log("开机完成，恢复守护服务");
        try {
            Intent s = new Intent(c, GuardService.class);
            s.setAction(GuardService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Throwable t) {
            LogBus.log("开机恢复失败: " + t);
        }
    }
}
