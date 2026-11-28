package com.vlab.autosleeper.data;

import android.content.Context;

import com.vlab.autosleeper.LogBus;
import com.vlab.autosleeper.core.PulseTable;
import com.vlab.autosleeper.core.SlotMath;
import com.vlab.autosleeper.core.Verdict;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * 曲线的唯一缓存与归档处。
 *
 * 替代原来的 PulseStore（9 个公共静态字段，谁都能改）。现在：
 *   - 内存里只有一份 {@link PulseTable}，写入只走 {@link #publishTable}；
 *   - 落盘写「所有有效格」，不再写 0..lastIndex。
 *     原实现跨天后 lastIndex 会变得很小，昨天 22:00-24:00 那段静默丢失。
 *   - 读回时当前格按本机时间重算，界面上的「距今多久」不会错。
 */
public final class PulseRepository {

    private static final String DIR = "pulse";
    /** 保留最近多少天。14 天足够回看，一天也就几 KB。 */
    private static final int KEEP_DAYS = 14;

    private static volatile PulseTable sTable = PulseTable.empty();
    private static volatile Verdict sVerdict;
    private static volatile boolean sSleeping;
    private static volatile long sUpdatedAt;

    private PulseRepository() {
    }

    // ------------------------------------------------------------ 内存视图

    public static PulseTable current() {
        return sTable;
    }

    /** 最近一次判定结果；还没判定过时为 null。 */
    public static Verdict verdict() {
        return sVerdict;
    }

    public static boolean sleeping() {
        return sSleeping;
    }

    public static long updatedAt() {
        return sUpdatedAt;
    }

    public static void publishTable(PulseTable table) {
        if (table == null) {
            return;
        }
        sTable = table;
        sUpdatedAt = System.currentTimeMillis();
    }

    public static void publishVerdict(Verdict verdict, boolean sleeping) {
        sVerdict = verdict;
        sSleeping = sleeping;
    }

    // ------------------------------------------------------------ 落盘

    /** 覆盖写当天文件。每次拉到的都是整表，所以不需要增量合并。 */
    public static synchronized void save(Context c, PulseTable table) {
        if (table == null || table.isEmpty()) {
            return;
        }
        try {
            File dir = dir(c);
            File f = new File(dir, dayName(System.currentTimeMillis()) + ".csv");
            int[] slots = table.slots();
            StringBuilder sb = new StringBuilder(4096);
            sb.append("#watch=").append(table.watchHour()).append(':').append(table.watchMinute())
                    .append(" valid=").append(table.validCount())
                    .append(" cur=").append(table.curSlot())
                    .append(" saved=").append(System.currentTimeMillis())
                    .append('\n');
            for (int i = 0; i < slots.length; i++) {
                if (SlotMath.valid(slots[i])) {
                    sb.append(i).append(',').append(slots[i]).append('\n');
                }
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Throwable ignored) {
                }
            } finally {
                fos.close();
            }
            prune(dir);
        } catch (Throwable t) {
            LogBus.log("曲线落盘失败: " + t);
        }
    }

    /**
     * 从磁盘读回曲线（内存里已有数据时不动，避免用旧文件盖掉新数据）。
     *
     * @return 是否真的读到了东西
     */
    public static synchronized boolean restore(Context c) {
        if (!sTable.isEmpty()) {
            return false;
        }
        try {
            File dir = dir(c);
            File f = new File(dir, dayName(System.currentTimeMillis()) + ".csv");
            if (!f.exists()) {
                f = latest(dir);
            }
            if (f == null || !f.exists()) {
                return false;
            }

            int[] slots = SlotMath.emptySlots();
            int watchHour = -1;
            int watchMinute = -1;

            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#")) {
                        String[] parts = line.substring(1).split(" ");
                        for (int i = 0; i < parts.length; i++) {
                            if (!parts[i].startsWith("watch=")) {
                                continue;
                            }
                            String[] hm = parts[i].substring(6).split(":");
                            if (hm.length >= 2) {
                                watchHour = parseInt(hm[0], -1);
                                watchMinute = parseInt(hm[1], -1);
                            }
                        }
                        continue;
                    }
                    int comma = line.indexOf(',');
                    if (comma <= 0) {
                        continue;
                    }
                    int idx = parseInt(line.substring(0, comma).trim(), -1);
                    int bpm = parseInt(line.substring(comma + 1).trim(), -1);
                    if (idx >= 0 && idx < SlotMath.SLOT_COUNT && SlotMath.valid(bpm)) {
                        slots[idx] = bpm;
                    }
                }
            } finally {
                br.close();
            }

            PulseTable table = PulseTable.restore(slots, watchHour, watchMinute,
                    System.currentTimeMillis());
            if (table.isEmpty()) {
                return false;
            }
            publishTable(table);
            LogBus.log("已从磁盘恢复 " + f.getName() + " 的 " + table.validCount() + " 格曲线");
            return true;
        } catch (Throwable t) {
            LogBus.log("读取历史曲线失败: " + t);
            return false;
        }
    }

    /** 历史文件列表，新的在前。 */
    public static File[] history(Context c) {
        File[] files = dir(c).listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().endsWith(".csv");
            }
        });
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, NEWEST_FIRST);
        return files;
    }

    /** 单个历史文件的概览：格数、范围、手表时刻。 */
    public static String summary(File f) {
        if (f == null) {
            return "（无）";
        }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String watch = "";
            int n = 0;
            int min = Integer.MAX_VALUE;
            int max = 0;
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("#")) {
                        String[] parts = line.substring(1).split(" ");
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i].startsWith("watch=")) {
                                watch = "  · 手表 " + parts[i].substring(6);
                            }
                        }
                        continue;
                    }
                    int comma = line.indexOf(',');
                    if (comma <= 0) {
                        continue;
                    }
                    int v = parseInt(line.substring(comma + 1).trim(), -1);
                    if (SlotMath.valid(v)) {
                        n++;
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    }
                }
            } finally {
                br.close();
            }
            if (n == 0) {
                return f.getName() + "   （空）";
            }
            return String.format(Locale.US, "%s   %d 格   %d~%d bpm%s",
                    f.getName().replace(".csv", ""), n, min, max, watch);
        } catch (Throwable t) {
            return f.getName() + "   读取失败";
        }
    }

    // ------------------------------------------------------------ 内部

    private static final Comparator<File> NEWEST_FIRST = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            return b.getName().compareTo(a.getName());
        }
    };

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static String dayName(long at) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(at));
    }

    private static File latest(File dir) {
        File[] files = dir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().endsWith(".csv");
            }
        });
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, NEWEST_FIRST);
        return files[0];
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.getName().endsWith(".csv");
            }
        });
        if (files == null || files.length <= KEEP_DAYS) {
            return;
        }
        Arrays.sort(files, NEWEST_FIRST);
        for (int i = KEEP_DAYS; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
