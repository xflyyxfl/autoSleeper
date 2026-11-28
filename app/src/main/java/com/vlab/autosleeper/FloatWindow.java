package com.vlab.autosleeper;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮球管理。设置里勾选后由 GuardService 调 show()，
 * 拉完曲线调 refresh()。位置与透明度存在 AppConfig 里。
 */
public class FloatWindow {

    private static WindowManager wm;
    private static FloatBallView view;
    private static WindowManager.LayoutParams lp;
    private static AppConfig cfg;
    private static int ballSize;

    public static boolean hasPermission(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static Intent requestIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
    }

    @SuppressLint("InflateParams")
    public static synchronized void show(Context c) {
        if (view != null || !hasPermission(c)) {
            return;
        }
        Context app = c.getApplicationContext();
        cfg = new AppConfig(app);
        wm = (WindowManager) app.getSystemService(Service.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        ballSize = Math.round(app.getResources().getDisplayMetrics().density * 48);
        // 必须用 applicationContext：单次测量几秒后 Service 就 stopSelf，
        // 若 View 持有 Service，窗口残留会导致后续刷新/点击时崩溃
        view = new FloatBallView(app);

        lp = new WindowManager.LayoutParams(
                ballSize, ballSize,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        applyPosition(c);
        lp.alpha = cfg.floatAlpha() / 100f;

        view.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (lp == null || wm == null) {
                    return false;
                }
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - lp.x;
                        dy = e.getRawY() - lp.y;
                        downAt = System.currentTimeMillis();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try {
                            wm.updateViewLayout(view, lp);
                        } catch (Throwable ignored) {
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        // 没拖动就算点击，打开主界面
                        if (System.currentTimeMillis() - downAt < 300) {
                            Intent i = new Intent(v.getContext(), MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            v.getContext().startActivity(i);
                        } else {
                            savePosition(v.getContext());
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

        try {
            wm.addView(view, lp);
        } catch (Throwable t) {
            LogBus.log("悬浮窗添加失败: " + t.getMessage());
            view = null;
            wm = null;
        }
    }

    /** 按配置把球放回屏幕相对位置。 */
    private static void applyPosition(Context c) {
        if (lp == null || ballSize <= 0) {
            return;
        }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int x = (int) (dm.widthPixels * cfg.floatX() / 1000f) - ballSize / 2;
        int y = (int) (dm.heightPixels * cfg.floatY() / 1000f) - ballSize / 2;
        lp.x = Math.max(0, Math.min(dm.widthPixels - ballSize, x));
        lp.y = Math.max(0, Math.min(dm.heightPixels - ballSize, y));
    }

    /** 拖动结束后保存新位置。 */
    private static void savePosition(Context c) {
        if (lp == null || cfg == null) {
            return;
        }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int cx = lp.x + ballSize / 2;
        int cy = lp.y + ballSize / 2;
        cfg.setFloatX((int) (cx * 1000f / dm.widthPixels));
        cfg.setFloatY((int) (cy * 1000f / dm.heightPixels));
    }

    /** 配置变了（透明度／位置）立刻生效。 */
    public static synchronized void apply(Context c) {
        if (view == null || wm == null || lp == null) {
            return;
        }
        cfg = new AppConfig(c);
        lp.alpha = cfg.floatAlpha() / 100f;
        applyPosition(c);
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable ignored) {
        }
        try {
            view.refresh();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void refresh() {
        try {
            if (view != null) {
                view.refresh();
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void hide() {
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        view = null;
        wm = null;
        lp = null;
    }
}
