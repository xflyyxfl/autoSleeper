package com.vlab.autosleeper.core;

/**
 * 相对基线四票打分。高级模式，默认关闭。
 *
 * 清醒看剧时心率被剧情拉扯：整体偏高、波动大、有突升；
 * 入睡后整体下移、逐格平稳、不再回升。据此打四票：
 *   下行  相对基线下降 >= dropBpm      +40（微降 +20，高于基线 -30）
 *   平稳  标准差 < 3                   +20（>6 扣 15）
 *   未回升 中位数没有明显反弹           +20（回升 >6 扣 20）
 *   无突升 窗口最大值 <= 基线 + 10      违反扣 25
 *
 * 得分 >= 60 记为「本轮成立」，是否真的判睡由 {@link ConsecutiveGate} 决定。
 *
 * 关于兜底：以前这里硬编码 ABS_SLEEP_BPM = 55，用户把 M 改成 50 兜底还是 55，
 * 现在兜底阈值直接取用户设的 M（threshold），改了就跟着变。
 */
public final class BaselineStrategy implements SleepStrategy {

    /** 判定成立的分数线。 */
    public static final int PASS_SCORE = 60;

    private final int dropBpm;
    private final int absFallbackBpm;

    private double baseline;
    private int prevMedian;

    /**
     * @param baseline      上次学到的基线，<=0 表示没有
     * @param dropBpm       相对基线下降多少算「明显下行」
     * @param absFallbackBpm 低心率兜底阈值，<=0 关闭兜底
     */
    public BaselineStrategy(double baseline, int dropBpm, int absFallbackBpm) {
        this.baseline = Math.max(0d, baseline);
        this.dropBpm = Math.max(2, dropBpm);
        this.absFallbackBpm = Math.max(0, absFallbackBpm);
    }

    public double baseline() {
        return baseline;
    }

    @Override
    public JudgeKind kind() {
        return JudgeKind.BASELINE;
    }

    @Override
    public void reset() {
        baseline = 0d;
        prevMedian = 0;
    }

    @Override
    public Verdict evaluate(PulseWindow window) {
        if (window == null || window.isEmpty() || window.filled() < 2) {
            return Verdict.none(Reason.NOT_ENOUGH, JudgeKind.BASELINE);
        }

        int median = window.median();
        int peak = window.max();
        double sd = window.sd();

        boolean init = baseline <= 0d;
        if (init) {
            baseline = median;
        } else if (median > baseline) {
            // 清醒时心率上抬，基线跟着上移。这里不做「睡着时不跟」的特判：
            // 睡着意味着 median <= baseline，本来就不会走进这个分支。
            baseline = baseline * 0.7d + median * 0.3d;
        }

        int score = 0;
        Reason reason = Reason.NO_DROP;
        int drop = (int) Math.round(baseline - median);

        if (drop >= dropBpm) {
            score += 40;
            reason = Reason.DROP;
        } else if (drop >= dropBpm - 2) {
            score += 20;
            reason = Reason.DROP;
        } else if (drop < 0) {
            score -= 30;
            reason = Reason.NO_DROP;
        }

        if (sd < 3d) {
            score += 20;
        } else if (sd > 6d) {
            score -= 15;
        }

        if (prevMedian > 0) {
            if (median <= prevMedian + 1) {
                score += 20;
            } else if (median > prevMedian + 6) {
                score -= 20;
            }
        }

        if (peak > baseline + 10d) {
            score -= 25;
            reason = Reason.JUMP;
        }

        score = Math.max(0, Math.min(100, score));

        // 低心率 + 平稳的兜底：应对「一启动守护人已经在床上」的场景
        boolean lowFlat = absFallbackBpm > 0 && median <= absFallbackBpm && sd < 4d;
        if (lowFlat && reason != Reason.JUMP) {
            reason = Reason.LOW_FLAT;
        }

        boolean candidate = score >= PASS_SCORE || lowFlat;
        if (init) {
            candidate = false;
            reason = Reason.BASELINE_INIT;
        }

        prevMedian = median;

        return new Verdict(median, window.min(), peak, sd, score, candidate, reason, JudgeKind.BASELINE);
    }
}
