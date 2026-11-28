package com.vlab.autosleeper;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;

import com.vlab.autosleeper.core.JudgeKind;
import com.vlab.autosleeper.core.MeasureMode;
import com.vlab.autosleeper.data.Config;
import com.vlab.autosleeper.data.ConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置与控件的绑定表。
 *
 * 以前每个参数要写两遍：loadConfig 里一遍 get/setText，saveConfig 里一遍 parse/set。
 * 现在每个控件只声明一行，读和写都走同一份声明，不会出现「加了参数忘了存」。
 *
 * 新增一个参数的改动量：ConfigSpec 加一行 + 这里加一行 + 布局加一个控件。
 */
final class ConfigBinder {

    private final List<Runnable> loaders = new ArrayList<>();
    private final List<Runnable> savers = new ArrayList<>();
    private final Config cfg;
    private final EditText etAddr;

    ConfigBinder(Activity a, Config cfg) {
        this.cfg = cfg;

        etAddr = a.findViewById(R.id.etAddr);

        intField(a, R.id.etInterval, ConfigSpec.INTERVAL_MIN);
        intField(a, R.id.etMonitor, ConfigSpec.MONITOR_SEC);
        intField(a, R.id.etThreshold, ConfigSpec.THRESHOLD);
        intField(a, R.id.etHold, ConfigSpec.HOLD_SLOTS);
        intField(a, R.id.etConfirm, ConfigSpec.CONFIRM_ROUNDS);

        seekField(a, R.id.sbAlpha, ConfigSpec.FLOAT_ALPHA, 20);
        seekField(a, R.id.sbX, ConfigSpec.FLOAT_X, 0);
        seekField(a, R.id.sbY, ConfigSpec.FLOAT_Y, 0);

        boolField(a, R.id.cbAutoLock, ConfigSpec.AUTO_LOCK);
        boolField(a, R.id.cbBoot, ConfigSpec.RESUME_ON_BOOT);
        boolField(a, R.id.cbFloat, ConfigSpec.FLOAT_ENABLED);

        final CheckBox cbBaseline = a.findViewById(R.id.cbBaseline);
        loaders.add(() -> cbBaseline.setChecked(cfg.judgeKind() == JudgeKind.BASELINE));
        savers.add(() -> cfg.setJudgeKind(cbBaseline.isChecked()
                ? JudgeKind.BASELINE : JudgeKind.THRESHOLD));

        final CheckBox cbContinuous = a.findViewById(R.id.cbContinuous);
        loaders.add(() -> cbContinuous.setChecked(cfg.measureMode() == MeasureMode.CONTINUOUS));
        savers.add(() -> cfg.setMeasureMode(cbContinuous.isChecked()
                ? MeasureMode.CONTINUOUS : MeasureMode.PULSE));
    }

    private void intField(Activity a, int id, final ConfigSpec.Key<Integer> key) {
        final EditText view = a.findViewById(id);
        loaders.add(() -> view.setText(String.valueOf(cfg.getInt(key))));
        savers.add(() -> cfg.putInt(key, parseInt(view.getText().toString(), cfg.getInt(key))));
    }

    private void boolField(Activity a, int id, final ConfigSpec.Key<Boolean> key) {
        final CheckBox view = a.findViewById(id);
        loaders.add(() -> view.setChecked(cfg.getBool(key)));
        savers.add(() -> cfg.putBool(key, view.isChecked()));
    }

    /** SeekBar 的 progress 与真实值之间可能有固定偏移（透明度从 20 起）。 */
    private void seekField(Activity a, int id, final ConfigSpec.Key<Integer> key, final int offset) {
        final SeekBar view = a.findViewById(id);
        loaders.add(() -> view.setProgress(cfg.getInt(key) - offset));
        savers.add(() -> cfg.putInt(key, view.getProgress() + offset));
    }

    void load() {
        etAddr.setText(cfg.addr());
        for (int i = 0; i < loaders.size(); i++) {
            loaders.get(i).run();
        }
    }

    /**
     * 校验并保存。
     *
     * @return false 表示地址格式不对，调用方应提示用户
     */
    boolean save() {
        String addr = etAddr.getText().toString().trim();
        if (addr.length() < 17) {
            return false;
        }
        cfg.setAddr(addr);
        for (int i = 0; i < savers.size(); i++) {
            savers.get(i).run();
        }
        return true;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }
}
