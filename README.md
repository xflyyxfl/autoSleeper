# autoSleeper · 睡眠守护

监听 Haylou Solar LS05 手环的实时心率，判断是否入睡；入睡后发出广播，由 Tasker / AutoJS 接手暂停播放并锁屏。

## 为什么是心率

手环不提供「当前是否睡着」这个字段——睡眠记录是固件把一段时间「密封成段」之后才能同步的，半夜查不到当前这段。

但心率是手环愿意实时给的。清醒看剧时心率被剧情拉扯（有突升、波动大），入睡后整体下移、逐窗平稳、不再回升。这个形状差异就是判据。

## 判定逻辑

每轮连上手表，采样 45 秒，然后按四项打分：

| 项 | 条件 | 分值 |
|---|---|---|
| 下行 | 中位数 ≤ 基线 − 5 bpm | +40（微降 +20） |
| 平稳 | 标准差 < 3 | +20（>6 扣 15） |
| 未回升 | 中位数 ≤ 上一窗 + 1 | +20（明显回升扣 20） |
| 无突升 | 最大值 ≤ 基线 + 12 | 否则扣 25 |

得分 ≥ 60 且**连续 2 个窗口**成立 → 判睡。基线在清醒时缓慢跟随，判睡后锁定。

## 协议

来源：[eralpozcan/haylou-ls05](https://github.com/eralpozcan/haylou-ls05)，针对固件 `RH192IBV003006` 真机验证。

| 项 | 值 |
|---|---|
| 命令通道 | `00006001-0000-1000-8000-00805f9b34fb`（write-without-response） |
| 通知通道 | `00006002-0000-1000-8000-00805f9b34fb` |
| 订阅后握手 | 手表主动发 `1c` |
| 实时心率 | 写 `16` → 回 `16 11 00 <bpm>`，约 1 帧/秒，需佩戴 |
| 中断标志 | `16 11` 空帧 / `e5 00 ff 00` 传感器无效 |

## 使用

1. 安装 APK，授予蓝牙权限（Android 12+ 是「附近的设备」，不是定位）。
2. 手机蓝牙里的 `Haylou Solar` 保留配对即可；若连接不上，点界面里的「系统配对」。
3. 睡前点「开始守护」。建议同时在系统设置里给本应用加电池白名单，否则国产 ROM 会杀后台。
4. 先点「立即测一次」确认能读到心率帧，再开始守护。

## 给 Tasker / AutoJS 的接口

应用会发三种广播，Tasker 里用「事件 → 系统 → 收到的意图」监听：

| 广播 | 时机 | 附加数据 |
|---|---|---|
| `com.vlab.autosleeper.ASLEEP` | 判定入睡（每次入睡只发一次） | `bpm` `score` `at` |
| `com.vlab.autosleeper.AWAKE` | 从睡眠中恢复 | `bpm` `score` `at` |
| `com.vlab.autosleeper.STATE` | 每一轮结果 | `bpm` `score` `asleep` `at` |

同时会把最新状态写到 `/sdcard/Android/data/com.vlab.autosleeper/files/state.txt`，格式 `asleep,62,80,1732...`。

Tasker 收到 ASLEEP 后可以执行（需一次 ADB Wifi 配对）：

```sh
am force-stop com.netease.cloudmusic   # 或对应播放器包名
input keyevent 26                      # 电源键锁屏
cmd media_session dispatch pause       # 兜底停播
```

## 构建

推送到 `main` 分支即触发 GitHub Actions，产物在 Releases 里：

```
https://github.com/xflyyxfl/autoSleeper/releases/latest
```

本地无 Android 环境也可以，CI 用 JDK 17 + AGP 8.2.0 + Gradle 8.2。

## 已知限制

- 手环单连接：守护期间 Hello Haylou 会抢不到连接，白天需要同步数据时先停止守护。
- 心率依赖佩戴，表带松了会出 `e5 00 ff`。
- 判定延迟本质是生理决定的：人躺下后要十几分钟才进入稳定睡眠，两个窗口约 20 分钟，属于正常量级。
