package com.vlab.autosleeper.data;

import com.vlab.autosleeper.core.JudgeKind;
import com.vlab.autosleeper.core.MeasureMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 全部可配置项的唯一声明处。
 *
 * 以前一个参数要改四处：AppConfig 的 getter、setter、MainActivity.loadConfig、
 * MainActivity.saveConfig，而且 setter 和 getter 的 clamp 范围还能不一致
 * （monitorSec 写入允许 60、读出来被抬到 120）。
 *
 * 现在每个参数一行声明：名字、默认值、范围、给人看的标签。
 * get/set/clamp 由 {@link Config} 统一实现，界面由 ConfigBinder 自动绑定。
 */
public final class ConfigSpec {

    /** SharedPreferences 文件名，沿用旧名以便平滑升级。 */
    public static final String PREF = "autosleeper";

    /** 配置结构版本，用于一次性迁移。 */
    public static final String MIGRATED_KEY = "schema2";

    public enum Kind {
        STRING, INT, BOOL, ENUM
    }

    /** 一个参数的完整定义。 */
    public static final class Key<T> {

        public final String name;
        public final String label;
        public final Kind kind;
        public final int min;
        public final int max;
        public final List<String> allowed;

        private final Object def;

        private Key(String name, String label, Kind kind, Object def,
                    int min, int max, List<String> allowed) {
            this.name = name;
            this.label = label;
            this.kind = kind;
            this.def = def;
            this.min = min;
            this.max = max;
            this.allowed = allowed;
        }

        @SuppressWarnings("unchecked")
        public T def() {
            return (T) def;
        }

        public int defInt() {
            return ((Integer) def).intValue();
        }

        public boolean defBool() {
            return ((Boolean) def).booleanValue();
        }

        public String defString() {
            return (String) def;
        }
    }

    private static Key<String> str(String name, String def, String label) {
        return new Key<String>(name, label, Kind.STRING, def, 0, 0,
                Collections.<String>emptyList());
    }

    private static Key<Integer> num(String name, int def, int min, int max, String label) {
        return new Key<Integer>(name, label, Kind.INT, Integer.valueOf(def), min, max,
                Collections.<String>emptyList());
    }

    private static Key<Boolean> bool(String name, boolean def, String label) {
        return new Key<Boolean>(name, label, Kind.BOOL, Boolean.valueOf(def), 0, 0,
                Collections.<String>emptyList());
    }

    private static Key<String> oneOf(String name, String def, String label, String... allowed) {
        return new Key<String>(name, label, Kind.ENUM, def, 0, 0, Arrays.asList(allowed));
    }

    // ------------------------------------------------------------ 手环

    public static final Key<String> ADDR =
            str("addr", "78:02:B7:6F:21:0B", "手表 MAC 地址");

    // ------------------------------------------------------------ 采样

    public static final Key<Integer> INTERVAL_MIN =
            num("interval", 10, 1, 120, "检测间隔（分钟）");

    /**
     * 每轮亮灯时长。
     * 固件要求一格内累计采样约 210 秒以上才落盘，低于这个值基本是白亮。
     */
    public static final Key<Integer> MONITOR_SEC =
            num("monitor", 240, 120, 600, "亮灯时长（秒）");

    public static final Key<String> MEASURE_KIND =
            oneOf("measureKind", MeasureMode.PULSE.name(), "测量模式",
                    MeasureMode.PULSE.name(), MeasureMode.CONTINUOUS.name());

    // ------------------------------------------------------------ 判定

    public static final Key<String> JUDGE_KIND =
            oneOf("judgeKind", JudgeKind.THRESHOLD.name(), "判定方式",
                    JudgeKind.THRESHOLD.name(), JudgeKind.BASELINE.name());

    /** 睡眠阈值 M：心率低于这个值才算「睡着」。 */
    public static final Key<Integer> THRESHOLD =
            num("threshold", 55, 35, 95, "睡眠阈值 M（bpm）");

    /**
     * 判定窗口长度，单位「格」（每格 10 分钟）。两种判定方式共用。
     * 以前阈值模式叫 holdMinutes、基线模式叫 windowSlots，两套并存的后果是
     * 界面上只显示一个，另一个在暗处生效。
     */
    public static final Key<Integer> HOLD_SLOTS =
            num("holdSlots", 2, 1, 12, "窗口持续格数（1 格 = 10 分钟）");

    /** 连续几轮成立才真正判睡，两种模式共用。 */
    public static final Key<Integer> CONFIRM_ROUNDS =
            num("confirmRounds", 2, 1, 5, "连续确认轮数");

    /** 基线模式：相对基线下降多少 bpm 记满分。 */
    public static final Key<Integer> DROP_BPM =
            num("drop", 5, 2, 20, "相对基线下降（bpm）");

    // ------------------------------------------------------------ 行为

    public static final Key<Boolean> AUTO_LOCK =
            bool("autoLock", true, "判定入睡后自动锁屏");

    public static final Key<Boolean> RESUME_ON_BOOT =
            bool("resumeOnBoot", true, "开机自动恢复守护");

    // ------------------------------------------------------------ 悬浮球

    public static final Key<Boolean> FLOAT_ENABLED =
            bool("floatEnabled", false, "显示悬浮心率球（可拖动）");

    public static final Key<Integer> FLOAT_X =
            num("floatX", 880, 0, 1000, "悬浮球水平位置");

    public static final Key<Integer> FLOAT_Y =
            num("floatY", 180, 0, 1000, "悬浮球垂直位置");

    public static final Key<Integer> FLOAT_ALPHA =
            num("floatAlpha", 85, 20, 100, "悬浮球透明度");

    private ConfigSpec() {
    }
}
