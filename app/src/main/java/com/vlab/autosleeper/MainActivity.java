package com.vlab.autosleeper;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.vlab.autosleeper.core.PulseTable;
import com.vlab.autosleeper.core.SlotMath;
import com.vlab.autosleeper.core.Verdict;
import com.vlab.autosleeper.data.Config;
import com.vlab.autosleeper.data.PulseRepository;
import com.vlab.autosleeper.flow.FlowRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：只负责「呈现」和「触发」，不承载任何业务规则。
 *
 * 配置读写交给 {@link ConfigBinder}，曲线与判定结果读 {@link PulseRepository}，
 * 运行状态问 {@link FlowRuntime}。界面不再直接连手环、不再自己算窗口。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 100;
    private static final long REFRESH_MS = 3000L;

    private EditText etAddr;
    private TextView tvStatus;
    private TextView tvNow;
    private TextView tvLog;
    private TextView tvChartInfo;
    private HeartChartView chart;
    private Button btnStopOnce;
    private Button btnTest;

    private Config cfg;
    private ConfigBinder binder;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 绑定配置期间抑制控件回调，避免 setChecked 触发一次假的用户操作。 */
    private boolean binding;
    /** 上次渲染过的日志版本；-1 保证首次进入一定渲染。 */
    private long lastLogVersion = -1L;

    private final LogBus.Sink sink = new LogBus.Sink() {
        @Override
        public void onLine(final String line) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    renderLogIfChanged();
                }
            });
        }
    };

    /**
     * 日志有变化才重建文本。
     *
     * 进入界面、从后台回到前台、以及每秒轮询都会走到这里，
     * 不再依赖「下一条日志到达」才刷新。
     */
    private void renderLogIfChanged() {
        long v = LogBus.version();
        if (v == lastLogVersion) {
            return;
        }
        lastLogVersion = v;
        tvLog.setText(LogBus.dumpReversed());
        tvLog.scrollTo(0, 0);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            renderLogIfChanged();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.install(this);
        setContentView(R.layout.activity_main);

        etAddr = findViewById(R.id.etAddr);
        tvStatus = findViewById(R.id.tvStatus);
        tvNow = findViewById(R.id.tvNow);
        tvLog = findViewById(R.id.tvLog);
        tvChartInfo = findViewById(R.id.tvChartInfo);
        chart = findViewById(R.id.chart);
        btnStopOnce = findViewById(R.id.btnStopOnce);
        btnTest = findViewById(R.id.btnTest);

        cfg = new Config(this);
        cfg.migrate();
        binder = new ConfigBinder(this, cfg);

        binding = true;
        binder.load();
        binding = false;

        // 服务没在跑时，界面也能显示上次落盘的曲线
        PulseRepository.restore(this);
        tvLog.setText(LogBus.dumpReversed());

        bindButtons();
        bindFloatControls();
        ensurePerms();
        refreshStatus();
    }

    private void bindButtons() {
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTestBtn = findViewById(R.id.btnTest);
        Button btnFetch = findViewById(R.id.btnFetch);
        Button btnLock = findViewById(R.id.btnLock);
        Button btnAdmin = findViewById(R.id.btnAdmin);
        Button btnPair = findViewById(R.id.btnPair);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnBattery = findViewById(R.id.btnBattery);
        Button btnHistory = findViewById(R.id.btnHistory);
        Button btnOverlayPerm = findViewById(R.id.btnOverlayPerm);

        btnStart.setOnClickListener(v -> {
            if (!prepare()) {
                return;
            }
            send(GuardService.ACTION_START, true);
            toast("开始守护");
        });

        btnStop.setOnClickListener(v -> {
            send(GuardService.ACTION_STOP, false);
            toast("正在停止守护");
        });

        // 测一次：开灯 → 按设置的亮灯时长跑 → 到时自动收尾（也可随时点「结束」）
        btnTestBtn.setOnClickListener(v -> {
            if (!prepare()) {
                return;
            }
            send(GuardService.ACTION_ONCE, true);
            toast("开始测量，约 " + cfg.monitorSec() + " 秒后可点「结束」提前收尾");
        });

        btnStopOnce.setOnClickListener(v -> {
            send(GuardService.ACTION_STOP_ONCE, false);
            toast("已结束亮灯，正在读取数据");
        });

        btnFetch.setOnClickListener(v -> {
            if (!hasBlePerms()) {
                ensurePerms();
                toast("缺少蓝牙权限，请授权后重试");
                return;
            }
            send(GuardService.ACTION_FETCH, true);
            toast("正在读取心率数据");
        });

        btnLock.setOnClickListener(v -> {
            if (!LockHelper.isActive(this)) {
                toast("请先激活锁屏权限");
                openAdmin();
                return;
            }
            LockHelper.lock(this);
            toast("已锁屏");
        });

        btnAdmin.setOnClickListener(v -> openAdmin());
        btnPair.setOnClickListener(v -> doPair());

        btnClear.setOnClickListener(v -> {
            LogBus.clear();
            tvLog.setText("");
        });

        btnBattery.setOnClickListener(v -> requestIgnoreBattery());
        btnHistory.setOnClickListener(v -> showHistory());

        btnOverlayPerm.setOnClickListener(v -> {
            try {
                startActivity(FloatWindow.requestIntent(this));
            } catch (Throwable t) {
                toast("打不开悬浮窗授权页");
            }
        });
    }

    private void bindFloatControls() {
        CheckBox cbFloatBox = findViewById(R.id.cbFloat);
        cbFloatBox.setOnCheckedChangeListener((button, checked) -> {
            if (binding) {
                return;
            }
            if (checked && !FloatWindow.hasPermission(this)) {
                toast("请先授权悬浮窗");
                binding = true;
                cbFloatBox.setChecked(false);
                binding = false;
                try {
                    startActivity(FloatWindow.requestIntent(this));
                } catch (Throwable ignored) {
                }
                return;
            }
            cfg.setFloatEnabled(checked);
            if (checked) {
                FloatWindow.show(this);
                toast("悬浮球已显示，拖动可改位置");
            } else {
                FloatWindow.hide();
            }
        });

        SeekBar sbAlpha = findViewById(R.id.sbAlpha);
        sbAlpha.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                cfg.setFloatAlpha(p + 20);
                FloatWindow.apply(MainActivity.this);
            }
        });

        SeekBar sbX = findViewById(R.id.sbX);
        sbX.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                cfg.setFloatX(p);
                FloatWindow.apply(MainActivity.this);
            }
        });

        SeekBar sbY = findViewById(R.id.sbY);
        sbY.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                cfg.setFloatY(p);
                FloatWindow.apply(MainActivity.this);
            }
        });
    }

    /** 保存配置 + 检查权限，任一失败就返回 false。 */
    private boolean prepare() {
        if (!binder.save()) {
            toast("MAC 地址格式不对，应为 AA:BB:CC:DD:EE:FF");
            return false;
        }
        if (!hasBlePerms()) {
            ensurePerms();
            toast("缺少蓝牙权限，请授权后重试");
            return false;
        }
        return true;
    }

    private void send(String action, boolean foreground) {
        Intent i = new Intent(this, GuardService.class);
        i.setAction(action);
        try {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            toast("启动失败：" + t.getMessage());
            LogBus.log("启动失败: " + t);
        }
    }

    // ------------------------------------------------------------ 状态

    private void refreshStatus() {
        boolean guarding = FlowRuntime.guardRunning();
        boolean measuring = FlowRuntime.onceRunning();

        if (guarding) {
            tvStatus.setText("守护运行中（" + cfg.measureMode().label()
                    + " · " + cfg.judgeKind().label() + "）");
        } else if (measuring) {
            tvStatus.setText("单次测量中…");
        } else {
            tvStatus.setText(R.string.status_idle);
        }

        btnStopOnce.setEnabled(measuring);
        btnTest.setEnabled(!guarding && !measuring);

        PulseTable table = PulseRepository.current();
        chart.setData(table, cfg.threshold());

        if (table.isEmpty()) {
            tvChartInfo.setText("还没有曲线数据，先点「测一次」");
            tvNow.setText("手环未连接");
            chart.setFooter("状态：无数据");
            return;
        }

        long ageMin = 0L;
        if (PulseRepository.updatedAt() > 0) {
            ageMin = (System.currentTimeMillis() - PulseRepository.updatedAt()) / 60000L;
        }
        tvChartInfo.setText(String.format(Locale.US,
                "共 %d 格有效（每格 %d 分钟）· 手表时间 %s · %d 分钟前读取",
                table.validCount(), SlotMath.SLOT_MINUTES, table.watchLabel(), ageMin));

        Verdict verdict = PulseRepository.verdict();
        if (verdict != null && verdict.median > 0) {
            tvNow.setText(String.format(Locale.US,
                    "中位心率 %d bpm · 阈值 %d · %s · %s",
                    verdict.median, cfg.threshold(),
                    PulseRepository.sleeping() ? "已判睡" : "清醒",
                    verdict.text()));
        } else {
            tvNow.setText("曲线已就绪");
        }

        chart.setFooter(PulseRepository.sleeping() ? "状态：已判睡" : "状态：清醒");
    }

    // ------------------------------------------------------------ 系统交互

    private void openAdmin() {
        if (LockHelper.isActive(this)) {
            toast("锁屏权限已激活");
            return;
        }
        try {
            startActivity(LockHelper.requestIntent(this));
        } catch (Throwable t) {
            toast("打不开设备管理器: " + t.getMessage());
        }
    }

    /** 加入电池优化白名单，否则息屏后进程会被系统冻结，整夜无法采样。 */
    private void requestIgnoreBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已经在白名单里了");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable ignored) {
                toast("请手动在系统设置里允许后台运行");
            }
        }
    }

    private void showHistory() {
        java.io.File[] files = PulseRepository.history(this);
        if (files.length == 0) {
            toast("还没有保存过曲线");
            return;
        }
        String[] items = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            items[i] = PulseRepository.summary(files[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("本地存档（" + files.length + " 天）")
                .setItems(items, null)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void doPair() {
        String addr = etAddr.getText().toString().trim();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            toast("蓝牙未开启");
            return;
        }
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(addr);
            if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
                toast("已经配对过了");
                LogBus.log("设备已处于配对状态");
                return;
            }
            boolean ok = dev.createBond();
            LogBus.log("发起系统配对: " + (ok ? "已请求，请在弹窗确认" : "请求失败"));
            toast(ok ? "已发起配对，注意屏幕弹窗" : "配对请求失败");
        } catch (Throwable t) {
            toast("地址无效: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ 权限

    private boolean hasBlePerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePerms() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(need, Manifest.permission.BLUETOOTH_CONNECT);
            addIfMissing(need, Manifest.permission.BLUETOOTH_SCAN);
        } else {
            addIfMissing(need, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(need, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERM);
        }
    }

    private void addIfMissing(List<String> need, String perm) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            need.add(perm);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (hasBlePerms()) {
                LogBus.log("权限已授予");
            } else {
                toast("蓝牙权限被拒绝，功能无法使用");
            }
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        LogBus.addSink(sink);
        binding = true;
        binder.load();
        binding = false;
        // 进入界面立刻把已有日志画出来，不必等下一条
        renderLogIfChanged();
        refreshStatus();
        if (cfg.floatEnabled() && FloatWindow.hasPermission(this)) {
            FloatWindow.show(this);
        }
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogBus.removeSink(sink);
        ui.removeCallbacks(ticker);
    }

    /** 从悬浮球点进来时 Activity 可能已在栈中，只走这里不走 onResume，也需要补一次刷新。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderLogIfChanged();
        refreshStatus();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** SeekBar 只要进度变化，其余回调为空。 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
