package com.vlab.autosleeper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 极简日志总线：服务在后台线程写，界面在主线程读。
 */
public final class LogBus {

    public interface Sink {
        void onLine(String line);
    }

    private static final int MAX_LINES = 400;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    /** 每次写入自增。界面靠它判断「有没有新内容」，不必每次重建整段文本。 */
    private static volatile long version = 0L;

    private LogBus() {
    }

    public static long version() {
        return version;
    }

    public static void addSink(Sink s) {
        if (s != null && !SINKS.contains(s)) {
            SINKS.add(s);
        }
    }

    public static void removeSink(Sink s) {
        SINKS.remove(s);
    }

    public static void log(String msg) {
        String ts;
        synchronized (FMT) {
            ts = FMT.format(new Date());
        }
        String line = ts + "  " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        android.util.Log.i("AutoSleeper", line);
        version++;
        for (Sink s : SINKS) {
            try {
                s.onLine(line);
            } catch (Throwable ignored) {
            }
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** 倒序输出：最新的在最上面，给界面用。 */
    public static String dumpReversed() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            java.util.Iterator<String> it = LINES.descendingIterator();
            while (it.hasNext()) {
                sb.append(it.next()).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
        version++;
    }
}
