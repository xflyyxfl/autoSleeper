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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 100;

    private EditText etAddr;
    private EditText etInterval;
    private EditText etMonitor;
    private EditText etThreshold;
    private EditText etHold;
    private CheckBox cbContinuous;
    private CheckBox cbAutoLock;
    private CheckBox cbBoot;
    private TextView tvStatus;
    private TextView tvNow;
    private TextView tvLog;
    private TextView tvChartInfo;
    private HeartChartView chart;

    private AppConfig cfg;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final LogBus.Sink sink = new LogBus.Sink() {
        @Override
        public void onLine(final String line) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    appendLog(line);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etAddr = findViewById(R.id.etAddr);
        etInterval = findViewById(R.id.etInterval);
        etMonitor = findViewById(R.id.etMonitor);
        etThreshold = findViewById(R.id.etThreshold);
        etHold = findViewById(R.id.etHold);
        cbContinuous = findViewById(R.id.cbContinuous);
        cbAutoLock = findViewById(R.id.cbAutoLock);
        cbBoot = findViewById(R.id.cbBoot);
        tvStatus = findViewById(R.id.tvStatus);
        tvNow = findViewById(R.id.tvNow);
        tvLog = findViewById(R.id.tvLog);
        tvChartInfo = findViewById(R.id.tvChartInfo);
        chart = findViewById(R.id.chart);

        cfg = new AppConfig(this);
        loadConfig();
        // 服务没在跑的时候，界面也能显示上次落盘的曲线
        PulseStore.load(this);
        tvLog.setText(LogBus.dumpReversed());

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTest = findViewById(R.id.btnTest);
        Button btnLock = findViewById(R.id.btnLock);
        Button btnAdmin = findViewById(R.id.btnAdmin);
        Button btnPair = findViewById(R.id.btnPair);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnBattery = findViewById(R.id.btnBattery);
        Button btnHistory = findViewById(R.id.btnHistory);

        btnStart.setOnClickListener(v -> {
            if (!saveConfig()) {
                return;
            }
            if (!hasBlePerms()) {
                ensurePerms();
                toast("缺少蓝牙权限，请授权后重试");
                return;
            }
            startGuardService();
            ui.postDelayed(this::refreshStatus, 600);
        });

        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, GuardService.class);
            i.setAction(GuardService.ACTION_STOP);
            startService(i);
            ui.postDelayed(this::refreshStatus, 600);
        });

        btnTest.setOnClickListener(v -> {
            if (!saveConfig()) {
                return;
            }
            if (!hasBlePerms()) {
                ensurePerms();
                toast("缺少蓝牙权限，请授权后重试");
                return;
            }
            Intent i = new Intent(this, GuardService.class);
            i.setAction(GuardService.ACTION_ONCE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            toast("开始单次测量");
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

        ensurePerms();
        refreshStatus();
    }

    private void loadConfig() {
        etAddr.setText(cfg.addr());
        etInterval.setText(String.valueOf(cfg.intervalMin()));
        etMonitor.setText(String.valueOf(cfg.monitorSec()));
        etThreshold.setText(String.valueOf(cfg.threshold()));
        etHold.setText(String.valueOf(cfg.holdMinutes()));
        cbContinuous.setChecked(cfg.measureMode() == 1);
        cbAutoLock.setChecked(cfg.autoLock());
        cbBoot.setChecked(cfg.resumeOnBoot());
    }

    private boolean saveConfig() {
        String addr = etAddr.getText().toString().trim();
        if (addr.length() < 17) {
            toast("MAC 地址格式不对，应为 AA:BB:CC:DD:EE:FF");
            return false;
        }
        cfg.setAddr(addr);
        cfg.setIntervalMin(parseInt(etInterval.getText().toString(), cfg.intervalMin()));
        cfg.setMonitorSec(parseInt(etMonitor.getText().toString(), cfg.monitorSec()));
        cfg.setThreshold(parseInt(etThreshold.getText().toString(), cfg.threshold()));
        cfg.setHoldMinutes(parseInt(etHold.getText().toString(), cfg.holdMinutes()));
        cfg.setMeasureMode(cbContinuous.isChecked() ? 1 : 0);
        cfg.setAutoLock(cbAutoLock.isChecked());
        cfg.setResumeOnBoot(cbBoot.isChecked());
        return true;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private void startGuardService() {
        cfg.resetBaseline();
        Intent i = new Intent(this, GuardService.class);
        i.setAction(GuardService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

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
            // 部分系统没有这个页面，退回到应用详情
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
        java.io.File[] fs = PulseStore.history(this);
        if (fs.length == 0) {
            toast("还没有保存过曲线");
            return;
        }
        String[] items = new String[fs.length];
        for (int i = 0; i < fs.length; i++) {
            items[i] = PulseStore.summary(fs[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("本地存档（" + fs.length + " 天）")
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
            int state = dev.getBondState();
            if (state == BluetoothDevice.BOND_BONDED) {
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

    private void appendLog(String line) {
        // 倒序：最新的日志显示在最上面，整块重设一次
        tvLog.setText(LogBus.dumpReversed());
        tvLog.scrollTo(0, 0);
    }

    private void refreshStatus() {
        boolean run = GuardService.running;
        if (run) {
            tvStatus.setText(cfg.measureMode() == 1 ? "守护运行中（连续监测）" : "守护运行中（脉冲省电）");
        } else {
            tvStatus.setText(R.string.status_idle);
        }

        int thr = cfg.threshold();
        chart.setData(PulseStore.snapshot(), PulseStore.lastIndex, PulseStore.cur,
                PulseStore.hour, PulseStore.minute, thr);

        if (!PulseStore.hasData()) {
            tvChartInfo.setText("还没有曲线数据，先点「立即测一次」");
            tvNow.setText("手环未连接");
            return;
        }

        String when = String.format(Locale.US, "%02d:%02d",
                Math.max(0, PulseStore.hour), Math.max(0, PulseStore.minute));
        String age = "";
        if (PulseStore.updatedAt > 0) {
            long min = (System.currentTimeMillis() - PulseStore.updatedAt) / 60000L;
            age = " · " + min + " 分钟前拉取";
        }
        tvChartInfo.setText(String.format(Locale.US,
                "共 %d 格有效（每格 %d 分钟）· 手表时间 %s%s",
                PulseStore.validCount, BandClient.SLOT_MINUTES, when, age));

        if (PulseStore.median > 0) {
            tvNow.setText(String.format(Locale.US,
                    "中位心率 %d bpm · 阈值 %d · %s%s",
                    PulseStore.median, PulseStore.threshold,
                    PulseStore.asleep ? "已判睡" : "清醒",
                    PulseStore.note.isEmpty() ? "" : " · " + PulseStore.note));
        } else {
            tvNow.setText("曲线已就绪");
        }

        chart.setFooter(PulseStore.asleep ? "状态：已判睡" : "状态：清醒");
    }

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

    @Override
    protected void onResume() {
        super.onResume();
        LogBus.addSink(sink);
        loadConfig();
        refreshStatus();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                ui.postDelayed(this, 3000);
            }
        }, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LogBus.removeSink(sink);
        ui.removeCallbacksAndMessages(null);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
