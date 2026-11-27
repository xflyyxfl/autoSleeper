package com.vlab.autosleeper;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 设备管理器接收器。只申请 force-lock 一项权限，用于判定入睡后锁屏。
 * 必须由用户在系统设置里手动激活一次，之后才能调用 lockNow()。
 */
public class SleepAdminReceiver extends DeviceAdminReceiver {

    public static ComponentName comp(Context c) {
        return new ComponentName(c.getApplicationContext(), SleepAdminReceiver.class);
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        LogBus.log("设备管理器已激活，可以自动锁屏");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        LogBus.log("设备管理器已停用，自动锁屏失效");
    }
}
