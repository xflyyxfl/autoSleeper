package com.vlab.autosleeper;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局异常捕获：进程崩溃前把完整栈写到 filesDir/crash.log，
 * 免得"程序闪退但拿不到任何线索"。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static boolean installed = false;

    private final Context ctx;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context c) {
        ctx = c.getApplicationContext();
        previous = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static synchronized void install(Context c) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(c));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date()) + " ===");
            pw.println("线程: " + t.getName());
            e.printStackTrace(pw);
            pw.flush();

            String text = sw.toString();
            LogBus.log("进程崩溃：\n" + text);

            // 写外部专属目录，用户用文件管理器也能翻到
            File dir = ctx.getExternalFilesDir(null);
            File f = new File(dir != null ? dir : ctx.getFilesDir(), "crash.log");
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(text.getBytes("UTF-8"));
            fos.write("\n".getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {
        }

        // 主线程崩溃后状态不可控，交给系统默认处理，让进程正常退出
        if (previous != null) {
            try {
                previous.uncaughtException(t, e);
            } catch (Throwable ignored) {
            }
        }
    }
}
