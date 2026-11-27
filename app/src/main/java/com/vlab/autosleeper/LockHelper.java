package com.vlab.autosleeper;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;

/**
 * 锁屏。无 root 情况下唯一可靠的方式是设备管理器 + lockNow()。
 *
 * 备选方案（都不如它）：
 *   - PowerManager.goToSleep 需要 DEVICE_POWER 签名权限
 *   - 无障碍服务模拟电源键，需要额外授权且不稳定
 */
public class LockHelper {

    private static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getApplicationContext()
                .getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /** 是否已经激活设备管理器。 */
    public static boolean isActive(Context c) {
        DevicePolicyManager d = dpm(c);
        try {
            return d != null && d.isAdminActive(SleepAdminReceiver.comp(c));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拉起系统激活页面。 */
    public static Intent requestIntent(Context c) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SleepAdminReceiver.comp(c));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "只用于判定入睡后自动锁屏，不会清除数据或修改密码。");
        return i;
    }

    /** 立即锁屏。返回是否成功。 */
    public static boolean lock(Context c) {
        DevicePolicyManager d = dpm(c);
        if (d == null) {
            LogBus.log("锁屏失败：拿不到 DevicePolicyManager");
            return false;
        }
        try {
            if (!d.isAdminActive(SleepAdminReceiver.comp(c))) {
                LogBus.log("锁屏失败：设备管理器未激活，请先在界面上点「激活锁屏权限」");
                return false;
            }
            d.lockNow();
            LogBus.log("已锁屏");
            return true;
        } catch (Throwable t) {
            LogBus.log("锁屏异常: " + t);
            return false;
        }
    }
}
