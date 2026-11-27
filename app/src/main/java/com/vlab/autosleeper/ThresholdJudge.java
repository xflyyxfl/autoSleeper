package com.vlab.autosleeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 简单阈值判定：心率低于 M，持续 N 分钟 → 判睡。
 *
 * 这是用户最直观的规则，比四票打分好解释：
 *   取最近 holdMinutes 对应的若干格（每格 10 分钟），
 *   若这些格「每一格都低于阈值」记一次成立；
 *   连续 needRounds 轮成立才真正判睡，避免一两次误采就锁屏。
 *
 * 清醒时会重新变回 false（解睡），方便第二天再用。
 */
public class ThresholdJudge {

    private final int threshold;
    private final int holdMinutes;
    private final int needRounds;

    private int rounds = 0;
    private int consecutive = 0;
    private boolean asleep = false;

    public ThresholdJudge(int threshold, int holdMinutes, int needRounds) {
        this.threshold = Math.max(30, threshold);
        this.holdMinutes = Math.max(BandClient.SLOT_MINUTES, holdMinutes);
        this.needRounds = Math.max(1, needRounds);
    }

    public int threshold() {
        return threshold;
    }

    public boolean isAsleep() {
        return asleep;
    }

    /**
     * @param all 最近若干格心率（时间正序，末尾最新），已经从完整曲线里裁好
     */
    public SleepJudge.Result feed(List<Integer> all) {
        SleepJudge.Result r = new SleepJudge.Result();
        r.score = 0;
        r.baseline = threshold;

        int need = Math.max(1, holdMinutes / BandClient.SLOT_MINUTES);
        if (all == null || all.isEmpty()) {
            r.note = "样本不足";
            r.asleep = asleep;
            return r;
        }

        // 取最近 need 格
        List<Integer> win = new ArrayList<>();
        int from = Math.max(0, all.size() - need);
        for (int i = from; i < all.size(); i++) {
            win.add(all.get(i));
        }

        int sum = 0;
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        int used = 0;
        boolean gap = false;
        for (int v : win) {
            if (v < 30 || v > 220) {
                gap = true;   // 有空格说明这段没在测，不能断言"持续低于阈值"
                continue;
            }
            sum += v;
            used++;
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        int avg = used == 0 ? 0 : sum / used;
        java.util.Collections.sort(win);
        int median = win.get(win.size() / 2);

        rounds++;

        boolean enough = win.size() >= need && used == win.size();
        boolean low = enough && !gap && median < threshold && max < threshold + 8;

        if (low) {
            consecutive++;
        } else {
            consecutive = 0;
            if (asleep) {
                asleep = false;
                LogBus.log("心率回升，解除睡眠状态");
            }
        }

        if (consecutive >= needRounds) {
            asleep = true;
        }

        StringBuilder note = new StringBuilder();
        if (!enough) {
            note.append(String.format(Locale.US, "还需 %d 格数据", need - win.size()));
        } else if (low) {
            note.append(String.format(Locale.US, "低于 %d 已 %d/%d 轮",
                    threshold, consecutive, needRounds));
        } else {
            note.append(median >= threshold
                    ? String.format(Locale.US, "高于阈值 %d", median)
                    : String.format(Locale.US, "有波动（峰值 %d）", max));
        }

        r.median = median;
        r.sd = max - min;
        r.min = min;
        r.max = max;
        r.asleep = asleep;
        r.note = note.toString();

        LogBus.log(String.format(Locale.US,
                "阈值判定: %d 格(共%d分钟) 均=%d 中位=%d 峰=%d 阈值=%d %s",
                win.size(), win.size() * BandClient.SLOT_MINUTES, avg, median, max, threshold,
                asleep ? "=> 判睡" : ""));
        return r;
    }
}
