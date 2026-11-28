package com.vlab.autosleeper.data;

import android.content.Context;

import com.vlab.autosleeper.core.Verdict;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 对外事件落盘：state.txt 与 verdict.log。
 *
 * 格式保持和 v1.x 完全一致，Tasker / AutoJS 那边的脚本不用改：
 *   state.txt    asleep,62,80,1732...
 *   verdict.log  2026-11-28 01:20:00,62,80,asleep,BELOW
 */
public final class EventStore {

    private static final String STATE_FILE = "state.txt";
    private static final String VERDICT_DIR = "pulse";
    private static final String VERDICT_FILE = "verdict.log";

    private EventStore() {
    }

    /** 覆盖写最近一次状态，供脚本轮询。 */
    public static void writeState(Context c, Verdict verdict, boolean sleeping) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, STATE_FILE);
            String content = String.format(Locale.US, "%s,%d,%d,%d%n",
                    sleeping ? "asleep" : "awake",
                    verdict == null ? 0 : verdict.median,
                    verdict == null ? 0 : verdict.score,
                    System.currentTimeMillis());
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
            // 外部存储不可用不该影响判定
        }
    }

    /** 追加一条判定记录，方便事后回看什么时候判的睡、为什么。 */
    public static void appendVerdict(Context c, Verdict verdict, boolean sleeping) {
        if (verdict == null) {
            return;
        }
        try {
            File dir = new File(c.getFilesDir(), VERDICT_DIR);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File f = new File(dir, VERDICT_FILE);
            String line = String.format(Locale.US, "%s,%d,%d,%s,%s%n",
                    stamp(System.currentTimeMillis()),
                    verdict.median, verdict.score,
                    sleeping ? "asleep" : "awake",
                    verdict.reason.name());
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stamp(long at) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(at));
    }
}
