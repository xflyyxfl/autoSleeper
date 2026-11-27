package com.vlab.autosleeper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 基于手环 10 分钟一格心率曲线的入睡判定。
 *
 * 曲线来自 18 fa：60 格 × 10 分钟 = 最近 10 小时。
 * 清醒看剧时心率被剧情拉扯，整体偏高、波动大、有突升；
 * 入睡后整体下移、逐格平稳、不再回升。
 *
 * 判据分四票，凑够 60 分且连续 needWindows 轮成立才判睡：
 *   下行  相对基线下降 ≥ dropBpm        +40
 *   平稳  窗口内标准差 < 3              +20
 *   未回升 中位数没有明显反弹             +20
 *   无突升 窗口最大值不超过基线 +10      不加分，违反则重罚
 *
 * 兜底：心率连续多轮低于 55 且极度平稳，即使没学到高基线也判睡
 * （应对「一启动守护就已经在床上」的情况）。
 */
public class SleepJudge {

    public static class Result {
        public int median;
        public double sd;
        public int score;
        public boolean asleep;
        public String note = "";
        public double baseline;
        public int max;
        public int min;
    }

    /** 绝对阈值兜底：低于这个值且平稳，视为睡眠心率。 */
    private static final int ABS_SLEEP_BPM = 55;
    private static final int ABS_NEED = 3;

    private double baseline;
    private int prevMedian;
    private int consecutive;
    private int absConsecutive;
    private boolean asleep;

    /** 是否已经有一轮真实数据打底（用于区分「刚启动」和「真清醒」）。 */
    private int rounds;

    private final int dropBpm;
    private final int needWindows;

    public SleepJudge(double baseline, int dropBpm, int needWindows) {
        this.baseline = baseline > 0 ? baseline : 0;
        this.dropBpm = Math.max(2, dropBpm);
        this.needWindows = Math.max(1, needWindows);
    }

    public double getBaseline() {
        return baseline;
    }

    public boolean isAsleep() {
        return asleep;
    }

    public int rounds() {
        return rounds;
    }

    public void reset() {
        baseline = 0;
        prevMedian = 0;
        consecutive = 0;
        absConsecutive = 0;
        asleep = false;
        rounds = 0;
    }

    /**
     * @param window 最近若干格的心率，时间正序（末尾是最新）。建议 3~4 格。
     */
    public Result feed(List<Integer> window) {
        Result r = new Result();
        if (window == null || window.size() < 2) {
            r.note = "样本不足";
            r.baseline = baseline;
            r.asleep = asleep;
            return r;
        }

        int n = window.size();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = window.get(i);
        }
        Arrays.sort(a);

        int median = a[n / 2];
        int min = a[0];
        int max = a[n - 1];
        double mean = 0;
        for (int v : a) {
            mean += v;
        }
        mean /= n;
        double var = 0;
        for (int v : a) {
            var += (v - mean) * (v - mean);
        }
        double sd = Math.sqrt(var / n);

        rounds++;

        if (baseline <= 0) {
            baseline = median;
            LogBus.log("基线初始化: " + median + " bpm");
        } else if (!asleep && median > baseline) {
            // 清醒时心率上抬，基线跟着上移（只上不下，避免自己漂到睡眠区）
            baseline = baseline * 0.7 + median * 0.3;
        }

        int score = 0;
        StringBuilder note = new StringBuilder();
        int drop = (int) Math.round(baseline - median);

        if (drop >= dropBpm) {
            score += 40;
            note.append("下行").append(drop);
        } else if (drop >= dropBpm - 2) {
            score += 20;
            note.append("微降").append(drop);
        } else if (drop < 0) {
            score -= 30;
            note.append("高于基线").append(-drop);
        }

        if (sd < 3.0) {
            score += 20;
            note.append(" 平稳");
        } else if (sd > 6.0) {
            score -= 15;
            note.append(" 波动大");
        }

        if (prevMedian > 0) {
            if (median <= prevMedian + 1) {
                score += 20;
                note.append(" 未回升");
            } else if (median > prevMedian + 6) {
                score -= 20;
                note.append(" 明显回升");
            }
        }

        if (max > baseline + 10) {
            score -= 25;
            note.append(" 有突升");
        }

        if (score < 0) {
            score = 0;
        }
        if (score > 100) {
            score = 100;
        }

        if (score >= 60) {
            consecutive++;
        } else {
            consecutive = 0;
            asleep = false;
        }

        // 绝对阈值兜底：低心率 + 平稳，连续 ABS_NEED 轮
        if (median <= ABS_SLEEP_BPM && sd < 4.0) {
            absConsecutive++;
        } else {
            absConsecutive = 0;
        }
        boolean absTrigger = absConsecutive >= ABS_NEED;
        boolean trigger = consecutive >= needWindows;

        if (trigger || absTrigger) {
            asleep = true;
            if (absTrigger && !trigger) {
                note.append(" 低心率兜底");
            }
        }

        r.median = median;
        r.sd = sd;
        r.score = score;
        r.asleep = asleep;
        r.note = note.toString().trim();
        r.baseline = baseline;
        r.max = max;
        r.min = min;

        LogBus.log(String.format(Locale.US,
                "窗口 n=%d 中位=%d 范围=%d-%d sd=%.1f 基线=%.1f 得分=%d | %s%s",
                n, median, min, max, sd, baseline, score, r.note,
                trigger ? "  => 判睡" : (asleep ? "  (已判睡)" : "")));
        prevMedian = median;
        return r;
    }

    /** 取窗口时用：把有效的最近若干格转成 List。 */
    public static List<Integer> window(List<Integer> all, int lastN) {
        List<Integer> out = new ArrayList<>();
        if (all == null || all.isEmpty()) {
            return out;
        }
        int from = Math.max(0, all.size() - lastN);
        for (int i = from; i < all.size(); i++) {
            out.add(all.get(i));
        }
        return out;
    }
}
