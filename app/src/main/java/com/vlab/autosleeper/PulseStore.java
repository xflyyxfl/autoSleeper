package com.vlab.autosleeper;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 最近一次拉取到的曲线与判定结果，进程内共享，供界面直接读取。
 *
 * 同时负责落盘：手环那张表只保留当天，进程一被杀、或者一整天没连上，
 * 那段时间的数据就再也拿不回来了。所以每拉到一次就写一份到
 * filesDir/pulse/YYYY-MM-DD.csv，重启后先读回来，界面不会白屏。
 *
 * 只保存有效格（30..220），空格靠 lastIndex 推断，文件很小（一天最多 144 行）。
 */
public class PulseStore {

    public static final int SLOTS = BandClient.SLOT_COUNT;

    private static final String DIR = "pulse";
    /** 保留最近多少天。14 天足够回看，也不会占空间（一天也就几 KB）。 */
    private static final int KEEP_DAYS = 14;

    private static int[] emptySlots() {
        int[] a = new int[SLOTS];
        Arrays.fill(a, BandClient.NO_DATA);
        return a;
    }

    /** 长度 SLOTS，值 30..220，-1 表示空格。 */
    public static volatile int[] slots = emptySlots();
    public static volatile int validCount = 0;
    /** 最近一个有效格的下标（从 cur 往前找）。 */
    public static volatile int lastIndex = -1;
    /** 当前时刻对应的格号（0..143）。跨天后必须以它为基准，否则会读到昨天的残留。 */
    public static volatile int cur = -1;
    public static volatile long updatedAt = 0L;

    /** 手表上报的采集时刻。 */
    public static volatile int hour = -1;
    public static volatile int minute = -1;

    /** 最近一次判定的结果。 */
    public static volatile int median = 0;
    public static volatile int threshold = 55;
    public static volatile boolean asleep = false;
    public static volatile String note = "";

    /** 最近一次写入的原始曲线（仅内存）。 */
    public static void update(int[] s, int valid, int lastIdx, int curIdx, int h, int m) {
        if (s == null || s.length == 0) {
            return;
        }
        int[] copy = new int[SLOTS];
        Arrays.fill(copy, BandClient.NO_DATA);
        System.arraycopy(s, 0, copy, 0, Math.min(s.length, SLOTS));
        slots = copy;
        validCount = valid;
        lastIndex = lastIdx;
        cur = curIdx;
        hour = h;
        minute = m;
        updatedAt = System.currentTimeMillis();
    }

    /** 判定结果。 */
    public static void setVerdict(int med, int thr, boolean sleep, String why) {
        median = med;
        threshold = thr;
        asleep = sleep;
        note = why == null ? "" : why;
    }

    public static int[] snapshot() {
        return slots.clone();
    }

    public static boolean hasData() {
        return validCount > 0;
    }

    // ---------------- 落盘 ----------------

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static String dayName(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }

    private static String stamp(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(t));
    }

    /** 把当前曲线写进当天的文件。覆盖写，因为每次拉的都是整表。 */
    public static synchronized void save(Context c) {
        if (validCount <= 0 || lastIndex < 0) {
            return;
        }
        try {
            int[] s = slots;
            File f = new File(dir(c), dayName(System.currentTimeMillis()) + ".csv");
            StringBuilder sb = new StringBuilder(2048);
            sb.append("#watch=").append(hour).append(':').append(minute)
                    .append(" valid=").append(validCount)
                    .append(" last=").append(lastIndex)
                    .append(" cur=").append(cur)
                    .append(" saved=").append(System.currentTimeMillis())
                    .append('\n');
            for (int i = 0; i <= lastIndex && i < s.length; i++) {
                if (s[i] >= 30 && s[i] <= 220) {
                    sb.append(i).append(',').append(s[i]).append('\n');
                }
            }
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            // 强制刷到存储介质，避免进程被系统杀掉时缓冲区里的内容丢失
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
            fos.close();
            prune(dir(c));
        } catch (Throwable t) {
            LogBus.log("曲线落盘失败: " + t);
        }
    }

    /** 读回当天的文件；当天没有就退回最近一份，界面至少不是空的。 */
    public static synchronized boolean load(Context c) {
        // 内存里已有更新鲜的数据（服务正在跑），不要用磁盘上的旧文件覆盖
        if (hasData()) {
            return false;
        }
        try {
            File d = dir(c);
            File f = new File(d, dayName(System.currentTimeMillis()) + ".csv");
            if (!f.exists()) {
                f = latestFile(d);
            }
            if (f == null || !f.exists()) {
                return false;
            }

            int[] arr = emptySlots();
            int h = -1, m = -1, last = -1, valid = 0, curIdx = -1;
            long saved = f.lastModified();

            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String line = br.readLine();
            if (line != null && line.startsWith("#")) {
                for (String kv : line.substring(1).split(" ")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = kv.substring(0, eq);
                    String v = kv.substring(eq + 1);
                    try {
                        if ("watch".equals(k)) {
                            String[] hm = v.split(":");
                            h = Integer.parseInt(hm[0]);
                            m = Integer.parseInt(hm[1]);
                        } else if ("last".equals(k)) {
                            last = Integer.parseInt(v);
                        } else if ("cur".equals(k)) {
                            curIdx = Integer.parseInt(v);
                        } else if ("valid".equals(k)) {
                            valid = Integer.parseInt(v);
                        } else if ("saved".equals(k)) {
                            saved = Long.parseLong(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                line = br.readLine();
            }
            while (line != null) {
                int comma = line.indexOf(',');
                if (comma > 0) {
                    try {
                        int idx = Integer.parseInt(line.substring(0, comma).trim());
                        int bpm = Integer.parseInt(line.substring(comma + 1).trim());
                        if (idx >= 0 && idx < SLOTS && bpm >= 30 && bpm <= 220) {
                            arr[idx] = bpm;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                line = br.readLine();
            }
            br.close();

            if (last < 0) {
                for (int i = SLOTS - 1; i >= 0; i--) {
                    if (arr[i] != BandClient.NO_DATA) {
                        last = i;
                        break;
                    }
                }
            }
            if (valid <= 0) {
                for (int v : arr) {
                    if (v != BandClient.NO_DATA) {
                        valid++;
                    }
                }
            }
            if (valid <= 0) {
                return false;
            }

            slots = arr;
            validCount = valid;
            lastIndex = last;
            cur = curIdx >= 0 ? curIdx : last;
            hour = h;
            minute = m;
            updatedAt = saved;
            LogBus.log("已从磁盘恢复 " + f.getName() + " 的 " + valid + " 格曲线");
            return true;
        } catch (Throwable t) {
            LogBus.log("读取历史曲线失败: " + t);
            return false;
        }
    }

    /** 追加一条判定记录，方便事后回看什么时候判的睡。 */
    public static synchronized void appendVerdict(Context c, int median, int thr,
                                                  boolean asleep, String note) {
        try {
            File f = new File(dir(c), "verdict.log");
            String line = String.format(Locale.US, "%s,%d,%d,%s,%s%n",
                    stamp(System.currentTimeMillis()), median, thr,
                    asleep ? "asleep" : "awake",
                    note == null ? "" : note.replace(',', ' '));
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(line.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /** 历史文件列表，新的在前。 */
    public static File[] history(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".csv"));
        if (fs == null) {
            return new File[0];
        }
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        return fs;
    }

    /** 单个历史文件的概览：格数、范围、手表时刻。 */
    public static String summary(File f) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String head = br.readLine();
            String hm = "";
            if (head != null && head.startsWith("#")) {
                for (String kv : head.substring(1).split(" ")) {
                    if (kv.startsWith("watch=")) {
                        hm = "  · 手表 " + kv.substring(6);
                    }
                }
            }
            int n = 0;
            int min = Integer.MAX_VALUE;
            int max = 0;
            String line;
            while ((line = br.readLine()) != null) {
                int comma = line.indexOf(',');
                if (comma <= 0) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(line.substring(comma + 1).trim());
                    if (v >= 30 && v <= 220) {
                        n++;
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    }
                } catch (Throwable ignored) {
                }
            }
            br.close();
            if (n == 0) {
                return f.getName() + "   （空）";
            }
            return String.format(Locale.US, "%s   %d 格   %d~%d bpm%s",
                    f.getName().replace(".csv", ""), n, min, max, hm);
        } catch (Throwable t) {
            return f.getName() + "   读取失败";
        }
    }

    private static File latestFile(File d) {
        File[] fs = d.listFiles((dir, n) -> n.endsWith(".csv"));
        if (fs == null || fs.length == 0) {
            return null;
        }
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        return fs[0];
    }

    /** 只保留最近 KEEP_DAYS 个文件。 */
    private static void prune(File d) {
        try {
            File[] fs = d.listFiles((dir, n) -> n.endsWith(".csv"));
            if (fs == null || fs.length <= KEEP_DAYS) {
                return;
            }
            Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
            for (int i = KEEP_DAYS; i < fs.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                fs[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
