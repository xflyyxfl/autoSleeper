package com.vlab.autosleeper;

import java.util.Arrays;
import java.util.List;

/**
 * 基于心率的入睡判定。
 *
 * 清醒看剧时心率被剧情拉扯：有突升、波动大。
 * 入睡后：整体下移、逐窗平稳、不再回升。
 * 所以判据是「下移 + 平稳 + 不回升 + 无突升」，四项凑够分数才算。
 */
public class SleepJudge {

    public static class Result {
        public int median;
        public double sd;
        public int score;
        public boolean asleep;
        public String note = "";
        public double baseline;
    }

    private double baseline;
    private int prevMedian;
    private int consecutive;
    private boolean asleep;

    private final int dropBpm;
    private final int needWindows;

    public SleepJudge(double baseline, int dropBpm, int needWindows) {
        this.baseline = baseline > 0 ? baseline : 0;
        this.dropBpm = dropBpm;
        this.needWindows = Math.max(1, needWindows);
    }

    public double getBaseline() {
        return baseline;
    }

    public boolean isAsleep() {
        return asleep;
    }

    public void reset() {
        baseline = 0;
        prevMedian = 0;
        consecutive = 0;
        asleep = false;
    }

    public Result feed(List<Integer> samples) {
        Result r = new Result();
        if (samples == null || samples.size() < 6) {
            r.note = "样本不足";
            r.baseline = baseline;
            r.asleep = asleep;
            return r;
        }

        int n = samples.size();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = samples.get(i);
        }
        Arrays.sort(a);

        int median = a[n / 2];
        double mean = 0;
        for (int v : a) {
            mean += v;
        }
        mean /= n;
        double var = 0;
        for (int v : a) {
            var += (v - mean) * (v - mean);
        }
        var /= n;
        double sd = Math.sqrt(var);
        int max = a[n - 1];
        int min = a[0];

        if (baseline <= 0) {
            baseline = median;
            LogBus.log("基线初始化: " + median + " bpm");
        }

        int score = 0;
        StringBuilder note = new StringBuilder();
        int drop = (int) Math.round(baseline - median);

        if (drop >= dropBpm) {
            score += 40;
            note.append("下行").append(drop).append(" ");
        } else if (drop >= dropBpm - 2) {
            score += 20;
            note.append("微降").append(drop).append(" ");
        } else if (drop < 0) {
            score -= 30;
            note.append("高于基线").append(-drop).append(" ");
        }

        if (sd < 3.0) {
            score += 20;
            note.append("平稳");
        } else if (sd > 6.0) {
            score -= 15;
            note.append("波动大");
        }

        if (prevMedian > 0 && median <= prevMedian + 1) {
            score += 20;
            note.append(" 未回升");
        } else if (prevMedian > 0 && median > prevMedian + 6) {
            score -= 20;
            note.append(" 明显回升");
        }

        if (max > baseline + 12) {
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
        boolean trigger = consecutive >= needWindows;
        if (trigger) {
            asleep = true;
        }

        r.median = median;
        r.sd = sd;
        r.score = score;
        r.asleep = asleep;
        r.note = note.toString().trim();
        r.baseline = baseline;

        // 清醒时缓慢跟随，睡着后锁定基线，避免自己漂下去
        if (!asleep && score < 40) {
            baseline = baseline * 0.85 + median * 0.15;
        }
        prevMedian = median;

        LogBus.log(String.format(java.util.Locale.US,
                "窗口 n=%d 中位=%d 极差=%d-%d sd=%.1f 基线=%.1f 得分=%d %s%s",
                n, median, min, max, sd, baseline, score, r.note,
                trigger ? "  => 判睡" : (asleep ? "  (已判睡)" : "")));
        return r;
    }
}
