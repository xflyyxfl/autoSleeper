package com.vlab.autosleeper;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 100;

    private EditText etAddr;
    private EditText etInterval;
    private EditText etSample;
    private TextView tvStatus;
    private TextView tvNow;
    private TextView tvLog;

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
        etSample = findViewById(R.id.etSample);
        tvStatus = findViewById(R.id.tvStatus);
        tvNow = findViewById(R.id.tvNow);
        tvLog = findViewById(R.id.tvLog);

        cfg = new AppConfig(this);
        etAddr.setText(cfg.addr());
        etInterval.setText(String.valueOf(cfg.intervalMin()));
        etSample.setText(String.valueOf(cfg.windowSlots()));
        tvLog.setText(LogBus.dump());

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTest = findViewById(R.id.btnTest);
        Button btnPair = findViewById(R.id.btnPair);
        Button btnClear = findViewById(R.id.btnClear);

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

        btnPair.setOnClickListener(v -> doPair());

        btnClear.setOnClickListener(v -> {
            LogBus.clear();
            tvLog.setText("");
        });

        ensurePerms();
        refreshStatus();
    }

    private boolean saveConfig() {
        String addr = etAddr.getText().toString().trim();
        if (addr.length() < 17) {
            toast("MAC 地址格式不对，应为 AA:BB:CC:DD:EE:FF");
            return false;
        }
        cfg.setAddr(addr);
        cfg.setIntervalMin(parseInt(etInterval.getText().toString(), cfg.intervalMin()));
        cfg.setWindowSlots(parseInt(etSample.getText().toString(), cfg.windowSlots()));
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
        // 重新开始守护时清零基线，让判定从当前清醒心率重新标定
        cfg.resetBaseline();
        Intent i = new Intent(this, GuardService.class);
        i.setAction(GuardService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
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
        tvLog.append(line);
        tvLog.append("\n");
        if (tvLog.getLineCount() > 500) {
            tvLog.setText(LogBus.dump());
        }
    }

    private void refreshStatus() {
        if (GuardService.running) {
            tvStatus.setText("守护运行中");
            tvNow.setText("每 " + cfg.intervalMin() + " 分钟读一次曲线 / 判定窗口 "
                    + cfg.windowSlots() + " 格（" + (cfg.windowSlots() * BandClient.SLOT_MINUTES) + " 分钟）");
        } else {
            tvStatus.setText(R.string.status_idle);
            tvNow.setText("手环未连接");
        }
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
