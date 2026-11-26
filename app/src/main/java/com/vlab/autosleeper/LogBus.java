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

    private LogBus() {
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

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
