# autoSleeper 重构方案 v2

> 目标：把「BLE 通信 / 数据模型 / 判定算法 / 调度 / 流程编排 / 界面」六件事彻底分开，
> 让**同一个能力既能被界面单独点一下调用，也能被守护流程串起来调用**，
> 且两条路径走的是**同一份代码**。

版本基线：v1.17.0（源码）／v1.19（APK）。本文只描述方案，不改代码。

---

## 0. 一句话结论

现在代码乱，根因不是"写得丑"，而是**同一件事有 2~4 份实现、同一个状态有 2 个持有者**。
所以重构的主轴不是"拆类"，而是立五条"唯一出口"：

| 唯一出口 | 现在有几个 | 收敛成 |
|---|---|---|
| BLE 会话生命周期 | 2（`doCycle` 自己管 → `fetchAndStore` 内部 close；手动路径自己管） | 1 个 `BandSession` |
| 「取最近 N 格」 | **4**（`GuardService.slotsWindow` 保留空格 / `SleepJudge.window` 去空格 / `PulseHistory.recent` 环形去空格 / `ThresholdJudge.feed` 再截一次） | 1 个 `PulseTable.window()` |
| 判定结果与睡/醒状态 | 2 套（`SleepJudge` 与 `ThresholdJudge`，且 Service 里还有第 3 个 `notifiedAsleep`） | 1 个 `JudgeEngine` + `Verdict` |
| 参数定义 | 3 处（`AppConfig` 的 getter、setter、`loadConfig/saveConfig` 手工搬运） | 1 份 `ConfigSpec` 声明 |
| 对外结果/事件 | 散落（广播 3 处 `send`、`writeState`、`PulseStore.appendVerdict`、`setVerdict`） | 1 个 `EventPublisher` |

---

## 1. 现状盘点

### 1.1 文件与真实职责

| 文件 | 行数 | 名义职责 | 实际还干了什么 |
|---|---|---|---|
| `GuardService.java` | 674 | 前台服务 | 线程管理、调度（算整点）、BLE 编排、判定选择、取窗口、落盘、刷悬浮球、发广播、写 state.txt、WakeLock、帧日志过滤 |
| `BandClient.java` | 560 | BLE 客户端 | 连接、解锁握手、命令写入、历史帧解析（硬编码下标）、心率历史缓冲、连接状态判断 |
| `MainActivity.java` | 527 | 界面 | 控件查找、配置读写、权限、系统配对、电池白名单、历史弹窗、状态轮询、日志渲染 |
| `PulseStore.java` | 339 | 曲线缓存与落盘 | 静态全局状态（9 个 volatile 字段，谁都能写）、CSV 读写、裁剪、verdict.log 追加、历史摘要 |
| `SleepJudge.java` | 220 | 四票打分判定 | 定义 `Result`（被另一个判定器借用）、基线跟随、连续计数、绝对阈值兜底 |
| `ThresholdJudge.java` | 126 | M/N 阈值判定 | 借用 `SleepJudge.Result`、再截一次窗口、自己一套连续计数 |
| `HeartChartView.java` | 226 | 画曲线 | 纯粹，无需动 |
| `FloatWindow/FloatBallView` | 314 | 悬浮球 | 读 `PulseStore` 静态字段 |
| `AppConfig.java` | 214 | 参数存储 | getter 与 setter 的 clamp 范围不一致 |
| `AlarmWatchdog/BootReceiver/LockHelper/CrashHandler/LogBus/SleepAdminReceiver` | 380 | 保活/锁屏/日志 | 基本合理，只需跟随接口变化 |

### 1.2 一次守护周期的真实调用链

```
onStartCommand(ACTION_START)
  └─ startWorker()                           ← new Thread（裸线程，无池、无取消语义）
       └─ loop()                             ← 算整点、待机 sleepQuiet、迟到跳过
            └─ cycle(boundary)               ← acquireWake + BLE_LOCK
                 └─ doCycle(boundary)        ← 连接 → 算 onAt/offAt → 开灯 → 等 → 关灯
                      ├─ sleepUntil()        ← 40 秒心跳 + 「断连就提前结束」（判断是坏的，见 P4）
                      ├─ fetchAndStore()     ← 拉取 + 诊断 + 落盘 + 刷球 + 重置开关 + close
                      │    └─ band.close()   ← 连接在这里被关掉，但调用方还以为 band 可用
                      ├─ slotsWindow()       ← 第 3 种取窗口（保留 -1）
                      ├─ simple.feed()/judge.feed()  ← 第 4 种截窗口在 simple 内部
                      ├─ PulseStore.setVerdict / appendVerdict
                      ├─ sendState / send(ASLEEP|AWAKE)   ← 状态来自 Service 的 notifiedAsleep
                      └─ LockHelper.lock()
```

手动拉取走另一条几乎一样、但细节不同的链：

```
onStartCommand(ACTION_FETCH) → startForeground → startFetch() → new Thread
  └─ tryLock(BLE_LOCK) → connect → fetchAndStore() → finally 判断 running 决定是否 stopSelf
```

**两条链共用 `fetchAndStore`，但它没有"只读"模式**，于是手动拉取也会累加 `staleRounds`、也可能去改心率开关（见 P6）。

---

## 2. 问题清单（按严重度排序）

> 标注规则：**【确证】**= 代码可直接读出；**【推断】**= 代码路径成立但需真机复现。

### P1【确证】「立即测一次」只亮 20 秒，与固件「需 ≥210 秒才落格」直接矛盾

`GuardService.java:258-260`：

```java
} else {                       // boundary == 0 即 ACTION_ONCE
    onAt = System.currentTimeMillis();
    offAt = onAt + 20000L;     // 20 秒
}
```

而 `README.md:42-47` 与 `AppConfig.java:42-47` 自己写明"需要连续采样约 3.5 分钟以上"。
后果：ONCE 永远拿不到新格，判定用的是**旧窗口**，可能用几十分钟前的数据判睡并锁屏。
这也是用户分不清「立即测一次」和「拉取心率」两个按钮的根源——它们的差别本来是"要不要开灯并判定"，但 ONCE 的开灯是无效的。

### P2【确证】同一个"取最近 N 格"有 4 份实现、3 种语义

| 实现 | 是否保留空格 | 环形跨天 | 输入 |
|---|---|---|---|
| `GuardService.slotsWindow()` | 保留 `-1` | 是 | `int[]` + cur |
| `SleepJudge.window()` | 去掉 | 否（依赖上游已排好） | `List` |
| `PulseHistory.recent()` | 去掉 | 是 | 内部数组 |
| `ThresholdJudge.feed()` 内部 | 再截一次 | 否 | `List` |

后果：
- `judgeMode=1` 时窗口里**看不到缺口**，`judgeMode=0` 时**能看到并拒绝判睡**。两种模式的抗缺口能力不同，同一份数据会给出不同结论，用户无法理解。
- `simple.feed()` 收到的是已经掐好的 `need` 格，内部又掐一次，`win.size() >= need` 恒成立，真正的判断退化成 `used == win.size()`，可读性极差。

### P3【确证】判定结果类型被借用，字段语义冲突

`ThresholdJudge.java:114`：

```java
r.sd = max - min;   // 字段名叫 sd（标准差），这里塞的是极差
r.score = 0;        // 阈值模式下 score 恒为 0
```

更糟的是 `used == 0`（窗口全空）时 `min = Integer.MAX_VALUE`、`max = Integer.MIN_VALUE`，
于是 `r.sd = max - min` 溢出成负数、`r.min/r.max` 是垃圾值，再经 `PulseStore.setVerdict()`
显示到界面上。`median` 也可能取到 `-1`（排序后取中间，窗口里含 `-1`）。

### P4【确证】"连接已断开就提前结束本轮"这个保护从来没生效

`BandClient.java:461-471`：

```java
public boolean isConnected() {
    BluetoothGatt g = gatt;
    if (g == null || !ready) return false;
    return g.getDevice() != null;   // ← 与连接状态无关，断开后仍非 null
}
```

`BluetoothGatt.getDevice()` 返回的是远端设备对象，与连接状态无关。
所以 `sleepUntil()` 里那句 `if (!band.isConnected())` 在真实断连时**不会**触发，
进程被冻结后仍会白等到 offAt，然后对一条已死的 GATT 发 `18 02`（静默失败）。

### P5【确证】看门狗 RESTART 会同时存在两个采样线程

`GuardService.java:106-117`：

```java
if (ACTION_RESTART.equals(act)) {
    stopGuard();          // 只是 worker.interrupt()
    running = false;
    worker = null;        // 丢弃引用，但不等待线程退出
    startWorker(false);   // 立刻起新线程
```

旧线程可能正卡在 `sleepUntil` 的 `sleepMs(40000)` 或 `band.connect()` 里，
`interrupt()` 后它还会继续走 `doCycle` 的后续步骤（见 P7）。两个线程各自持有 band，
靠 `BLE_LOCK` 串行化后，会出现"旧线程开灯、新线程关灯"的交叉时序。

### P6【确证】"手动拉取不改监测开关"这个承诺被自己打破

`GuardService.java:346-365` 的 `fetchAndStore()` 里有：

```java
if (h.ok() && h.ageMinutes() > 35) {
    staleRounds++;
    if (staleRounds >= 3) { ... setHeartRateMonitor(false); ... (true); ... (false); }
}
```

而 `startFetch()` 的注释（`:409`）写的是"不改监测开关、不判定、不锁屏"，
`:432` 也写着"只发解锁和 18 fa：不改监测开关"。
实际是：**手动点 3 次拉取，第 3 次会去改手环的心率开关**。`staleRounds` 是实例字段，被两条路径共享。

### P7【确证】收到"停止"后仍然会去点灯

`sleepUntil()`（`:464-485`）退出循环的条件有两个：到点了 / `stopRequested`。但它 `return` 时不区分，
`doCycle` 紧接着：

```java
sleepUntil(band, onAt);
band.setHeartRateMonitor(true);   // 即使是因为停止而退出，也照开不误
```

于是"停止守护"之后手环 LED 可能被重新点亮并**一直亮着**（后续 `18 02` 不再执行）。

### P8【确证】跨天残留的数据不会被落盘

`PulseStore.java:125`：

```java
for (int i = 0; i <= lastIndex && i < s.length; i++)
```

手环跨天不清表，凌晨 `cur` 很小（如 1），`lastIndex` 也就 0~1，
昨天 22:00–24:00 的 `slots[132..143]` 全部不会被写进 CSV。
而类注释（`:22`）说的是"只保存有效格"。这是**静默的数据丢失**，白天回看会发现前一天晚上是空的。

同时 `load()` 恢复时直接用文件里的 `cur`（`:233`），不按当前时间重算，
若兜底读的是昨天的文件，界面上的"手表时间 / 距今多久"都错。

### P9【确证】三套"连续次数"概念混在一起

| 参数 | 用在哪 | 含义 |
|---|---|---|
| `needRounds` | 仅 ThresholdJudge | 连续几轮成立 |
| `needWindows` | 仅 SleepJudge | 连续几个窗口成立 |
| `holdMinutes` | 仅 ThresholdJudge | 窗口时长（内部 ÷10 变格数） |
| `windowSlots` | 仅 SleepJudge | 窗口格数 |

界面上只暴露 `threshold` 和 `hold`，`needRounds` 用默认 2、`windowSlots` 用默认 3。
用户改 M/N 时不知道还有两套隐藏参数在起作用。而且 `SleepJudge` 里还有硬编码的
`ABS_SLEEP_BPM = 55`（`:38`）和 `ABS_NEED = 3`（`:39`）——**用户把 M 改成 50，兜底仍然按 55 判**。

### P10【确证】配置的范围在 setter 和 getter 里不一致

`AppConfig.monitorSec()` clamp 到 `120..600`，`setMonitorSec()` clamp 到 `60..600`。
写入 100 会被接受、读出来变 120，UI 的 `SeekBar/EditText` 显示值与真实生效值不同。
`threshold` 的 setter 存在，但 `MainActivity.saveConfig()` 里 `cfg.setThreshold(...)` 传的是解析失败的默认值 `cfg.threshold()`——不会出错，但说明"读写是手工搬运"的模式很脆。

### P11【确证】通知文案会被卡住

`onStartCommand`（`:118-126`）：

```java
startForeground(NOTIF_ID, buildNotification(once ? "单次测量中…" : "守护中…"));
if (running) {
    if (once) LogBus.log("（单次测量请先停止守护）");
    return START_STICKY;      // ← 通知已被改成"单次测量中…"，但不会再改回来
}
```

守护运行中点「立即测一次」，通知会永远停在"单次测量中…"。

### P12【确证】BLE 层遗留问题

- `connectedLatch`（`:157`）声明并在 `connect()` 里初始化，但 `connect()` 从没 `await` 它——死代码。
- `connect()` 只返回 `boolean`，失败原因（蓝牙未开 / 地址非法 / 服务缺失 / 超时 / 未解锁）只进日志，调用方无法区分"该提示用户开蓝牙"还是"该跳过本轮"。
- `signalReady()` 与 `failReady()` 语义重叠，注释里专门解释了"成功后必须用这个，否则会把 ready 自己清掉"——这是 API 设计缺陷的自我说明。
- 历史帧解析（`:325-357`）全用硬编码下标 `d[6]`、`d[5]`，无长度/`seq` 合法性校验，坏包会污染数组；`pulsePackets` 只用于日志，不参与完整性判断（丢包也当成功）。
- 帧日志过滤白名单（`0x18/0x16/0x1C/0x20/0x09`）写在 `GuardService.newBand()` 里（`:396`），是 BLE 细节泄漏到 Service。

### P13【确证】`PulseHistory.recent(144)` 返回的是"最近 144 格的有效值"，不是"最近 24 小时"

`recent()` 会跳过空格，所以它返回的列表长度 ≤144 但时间跨度可能远超 24 小时
（前一天残留也算）。`doCycle` 里 `h.recent(BandClient.SLOT_COUNT)` 再交给
`SleepJudge.window(all, 3)`，语义上是"最近 3 个**有数据**的格"，不是"最近 30 分钟"。
在长时间没数据时，会拿几小时前的 3 个格当"最近 3 格"用。

### P14【推断】手动拉取期间点"停止"会让 Service 在 fetch 线程还活着时销毁

`startFetch()` 的 finally（`:446-455`）：

```java
if (!running) { stopForeground(true); stopSelf(); }
```

若 fetch 进行中用户点了停止，`running` 已为 false，此时 `stopSelf()` 触发 `onDestroy`，
但 `fetchWorker` 仍在跑 BLE。`onDestroy` 里只 interrupt `worker`，不处理 `fetchWorker`。

### P15【确证】版本号与产物脱节

`app/build.gradle` 里 `versionName "1.17.0"`，而同目录产物已到 `autoSleeper-v1.19.apk`。
说明打包流程与源码版本没有单一来源。

---

## 3. 目标架构

### 3.1 分层

```
┌──────────────────────────────────────────────────────────────┐
│ ui/            MainActivity · ConfigBinder · StatusPresenter  │  只做「呈现」与「触发」
│                FloatWindow · FloatBallView · HeartChartView    │  不含任何业务规则
├──────────────────────────────────────────────────────────────┤
│ flow/          GuardFlow · OnceFlow · FetchFlow               │  流程 = 原子操作的有序组合
│                FlowPlan（待机/开灯/关灯时刻）                   │  唯一持有「循环」的地方
├──────────────────────────────────────────────────────────────┤
│ task/          Connect · LedOn · LedOff · FetchPulse          │  原子操作，每个都能单独调用
│                Judge · Publish · AutoLock                     │  统一签名，统一 OpResult
│                TaskRunner（互斥/超时/中断/WakeLock/日志）        │
├──────────────────────────────────────────────────────────────┤
│ band/          BandSession（唯一 BLE 出口）                    │  只管协议，不管业务
│                PulseCodec（帧 → PulseTable）                   │
│                BandCommand（20 01 / 18 01 / 18 02 / 18 fa）    │
├──────────────────────────────────────────────────────────────┤
│ core/          PulseTable · Window · Verdict · Transition      │  纯 Java，无 Android，可单测
│                JudgeEngine · SleepStrategy(2 实现)             │
│                BoundaryClock（整点计划）                        │
├──────────────────────────────────────────────────────────────┤
│ data/          ConfigSpec（唯一参数定义）· Config              │  唯一读写 SharedPreferences 的地方
│                PulseRepository · EventStore                    │
├──────────────────────────────────────────────────────────────┤
│ infra/         LogBus · CrashHandler · LockHelper              │  与业务无关的支撑
│                AlarmWatchdog · BootReceiver · SleepAdminReceiver│
└──────────────────────────────────────────────────────────────┘

service/GuardService  ← 只剩 3 件事：生命周期、前台通知、把 Intent action 路由到 flow
                         目标 ≤400 行（现在 674 行）
```

### 3.2 文件对照（旧 → 新）

| 旧 | 新 | 说明 |
|---|---|---|
| `BandClient.java` | `band/BandSession` + `band/PulseCodec` + `band/BandCommand` + `band/BandError` | 拆分；连接状态用真实回调状态；失败带原因 |
| `BandClient.PulseHistory` | `core/PulseTable` | 值对象，封装 `int[144]+curSlot`，提供 `window(n)` / `ageMinutes()` / `isEmpty()` |
| `SleepJudge` + `ThresholdJudge` | `core/JudgeEngine` + `core/strategy/{ThresholdStrategy,BaselineStrategy}` | 连续计数抽到 `ConsecutiveGate`，两个策略只负责打分 |
| `GuardService` | `service/GuardService` + `flow/{Guard,Once,Fetch}Flow` + `task/*` | 674 → 350 行 |
| `AppConfig` | `data/ConfigSpec` + `data/Config` | 一份声明，getter/setter/UI 绑定/clamp 全自动 |
| `PulseStore` | `data/PulseRepository`（曲线）+ `data/EventStore`（verdict.log / state.txt） | 去掉 9 个公共静态可变字段，改成一个不可变 `Snapshot` |
| `MainActivity` | `MainActivity` + `ui/ConfigBinder` + `ui/StatusPresenter` | 527 → ~200 行 |
| `LogBus` / `CrashHandler` / `LockHelper` / `AlarmWatchdog` / `BootReceiver` | `infra/*` | 仅移动与接口微调 |

---

## 4. 五个关键设计

### 4.1 BandSession：一次会话 = 一个 try-with-resources

**解决**：P4、P7、P12，以及"单独调用 vs 流程调用"的结构性障碍。

```java
// band/BandSession.java
public final class BandSession implements AutoCloseable {

    /** 唯一入口。拿不到 BLE 就当场上抛，调用方不必再写 if(!connect)。 */
    public static BandSession open(Config cfg) throws BandException {
        // 内部：静态 ReentrantLock.tryLock() → BusyException("手环正被占用")
        //       connectGatt → 等 onConnectionStateChange(CONNECTED)
        //       discoverServices → 校验 600ff/6001/6002
        //       订阅 CCCD → 发 20 01 → 等 20 04（带 3 次重试）
    }

    public void ledOn()  throws BandException;
    public void ledOff() throws BandException;
    public PulseTable fetchPulse(Duration timeout) throws BandException;

    /** 可中断等待：被 interrupt 抛 AbortedException，绝不再往下执行副作用。 */
    public void awaitUntil(long targetMs) throws BandException, AbortedException;

    @Override public void close();   // 幂等：disconnect + close + unlock
}

enum BandError { BT_OFF, BAD_ADDRESS, SERVICE_MISSING, NOT_AUTHED, TIMEOUT, DISCONNECTED, BUSY }
```

关键点：
1. **连接状态用真实回调维护**：`onConnectionStateChange` 里更新一个 `volatile State`，
   `isAlive()` 返回 `state == CONNECTED`。不再用 `getDevice() != null`。
2. **`awaitUntil` 返回即代表"到点了"**，被中断则抛异常。调用方拿不到"正常返回"就绝不点灯 —— 彻底修掉 P7。
3. **互斥在内部**，`open()` 拿不到锁直接抛 `BusyException`，不需要调用方 `tryLock/finally unlock`。
4. 帧日志过滤从 Service 移到 `PulseCodec`，`BandSession` 只在 debug 开关下打印原始帧。

于是"流程调用"和"单独调用"都长一样：

```java
// 独立调用：自己开 session
try (BandSession s = BandSession.open(cfg)) {
    PulseTable t = s.fetchPulse(12_000);
}
// 流程调用：复用外层 session
void runInSession(BandSession s) { ... }
```

### 4.2 PulseTable + Window：把"取最近 N 格"收敛成一份

**解决**：P2、P8、P13。

```java
public final class PulseTable {
    private final int[] slots;       // 长度 144，-1 = 空
    private final int curSlot;       // 当前时刻的格号
    private final int watchHour, watchMinute;
    private final long capturedAt;

    public int at(int slot);                       // 环形安全
    public Window window(int slots);               // 最近 n 格，含空洞信息
    public int ageMinutes();                       // 最近有效格距今
    public boolean isEmpty();
    public List<Slot> validRange();                // 落盘用：返回真实要写的区间（修 P8）
}

public final class Window {
    public final int[] values;                    // 允许 -1
    public int size();                            // 含空洞的真实长度
    public int filled();                          // 有效格数
    public boolean contiguous();                   // 全有效才 true
    public boolean isEmpty();
    public int median();  public int max();  public int min();  public double sd();
}
```

- 两个策略都吃 `Window`，**都看得到空洞**。阈值策略要求 `contiguous()`，基线策略要求 `filled() >= 2`。
  语义差异被显式写出来，而不是靠"谁来截窗口"隐式决定。
- 落盘用 `validRange()` 返回的连续区间（可能两段：`[0..cur]` 与 `[132..143]`），修掉 P8。
- `Window` 的统计量集中实现，`sd` 就是标准差，不会再被塞极差。

### 4.3 JudgeEngine + Transition：睡/醒状态只有一个持有者

**解决**：P3、P9。

```java
public interface SleepStrategy {
    Kind kind();
    Verdict evaluate(Window w);     // 纯函数：不改变任何状态，except 基线跟随
    void reset();
}

public final class JudgeEngine {
    private SleepStrategy strategy;
    private final ConsecutiveGate gate;    // 唯一的"连续 N 轮"实现
    private boolean asleep;                // 唯一的睡眠状态

    public Outcome update(Window w) {
        Verdict v = strategy.evaluate(w);
        boolean pass = v.asleepCandidate;
        gate.feed(pass);
        boolean next = gate.satisfied();
        Transition t = next == asleep ? Transition.NONE
                     : next ? Transition.FELL_ASLEEP : Transition.WOKE_UP;
        asleep = next;
        return new Outcome(v, t);
    }
}

public enum Transition { NONE, FELL_ASLEEP, WOKE_UP }
```

- **Service 不再有 `notifiedAsleep`**，只按 `Transition` 发一次广播/锁一次屏。状态机回到一处。
- 兜底阈值不再是硬编码：`ThresholdStrategy` 的兜底用同一份 `threshold - 8`，`BaselineStrategy`
  的绝对兜底读 `Config.absFallbackBpm`（默认 55，可关）。用户改 M，兜底跟着动。
- `Verdict` 自带 `reasonCode`（枚举）+ `params`（Map），日志/界面/广播都从同一结构渲染：

```java
public final class Verdict {
    public final int median, min, max;
    public final double sd;
    public final int score;          // 基线模式 0..100；阈值模式恒 100 或 0
    public final boolean asleepCandidate;
    public final Reason reason;      // BELOW_HOLD / NOT_ENOUGH / GAP / RISING / ...
    public final StrategyKind by;
}
```

### 4.4 BoundaryClock：调度变成纯函数，可单测

**解决**：整点计算与"迟到跳过"逻辑埋在 `loop()` 里、无法单测。

```java
public final class BoundaryClock {
    public static Plan plan(long nowMs, int intervalMin, int monitorSec) {
        long boundary = nextAlign(nowMs, intervalMin * 60_000L);
        long ledOn    = boundary - monitorSec * 1000L;
        long connectAt= ledOn - 55_000L;         // 提前 55 秒连表（原 50 秒）
        long ledOff   = boundary + 15_000L;      // 必须跨过整点结算
        return new Plan(connectAt, ledOn, ledOff, boundary);
    }
    /** 唤醒后迟到判定：返回 SKIP 原因，null 表示可以正常执行。 */
    public static SkipReason verify(Plan p, long nowMs) {
        if (nowMs - p.ledOnAt > 20_000L) return SkipReason.TOO_LATE;
        return null;
    }
}
```

`GuardFlow` 只做：`Plan p = plan(); await(p.connectAt); verify(); run(p);`
所有"差多少秒、跳过还是执行"的规则集中在此，配 8 个单测覆盖（整点、跨天、DST、迟到 1s/21s、interval=7 等）。

### 4.5 ConfigSpec：参数只定义一次

**解决**：P9、P10、P15，以及"新增一个参数要改 4 处"的脆弱性。

```java
public final class ConfigSpec {
    public static final Key<String>  ADDR        = Key.str("addr", "78:02:B7:6F:21:0B");
    public static final Key<Integer> INTERVAL_MIN= Key.num("interval", 10, 1, 120, "检测间隔(分钟)");
    public static final Key<Integer> MONITOR_SEC = Key.num("monitor", 240, 120, 600, "亮灯时长(秒)");
    public static final Key<Integer> THRESHOLD   = Key.num("threshold", 55, 35, 95, "睡眠阈值 M");
    public static final Key<Integer> HOLD_SLOTS  = Key.num("holdSlots", 2, 1, 12, "持续格数(×10 分钟)");
    public static final Key<Integer> CONFIRM     = Key.num("confirmRounds", 2, 1, 5, "连续确认轮数");
    public static final Key<Boolean> AUTO_LOCK   = Key.bool("autoLock", true, "入睡后自动锁屏");
    public static final Key<MeasureMode> MEASURE = Key.enumOf("measureMode", MeasureMode.PULSE);
    public static final Key<JudgeKind>  JUDGE    = Key.enumOf("judgeMode", JudgeKind.THRESHOLD);
    public static final Key<Integer> VERSION_CODE= Key.num("versionCode", 0, 0, 99999, "构建号");
}
```

- `Key` 自带 `default / min / max / label`，`get` 时统一 clamp，**setter 不再各写一遍**。
- `ConfigBinder` 按 Key 列表自动建控件、自动 load/save，新增参数只改一行。
- `JudgeKind` / `MeasureMode` 存成字符串，源码里不再出现 `== 1` 这类魔法数。
- 三套"连续次数"合并成两个：`HOLD_SLOTS`（窗口长度）+ `CONFIRM`（共用确认轮数）。
- `VERSION_CODE` 进配置后，APK 文件名由 `versionName + versionCode` 生成，单一来源（修 P15）。
- 界面按 `JUDGE` 的值只显示相关参数（阈值模式显示 M/格数，基线模式显示 drop/window），
  彻底消除"改了没用"的困惑。

---

## 5. 三种调用方式（用户核心诉求）

### 5.1 单独调用：任意原子操作

```java
// 测一次：按设置的亮灯时长跑，到点自动收尾
SleepGateway.once().run(result -> ui.render(result));

// 也可以中途手动结束：只截断「亮灯」这一段，后续拉取/判定照常执行
SleepGateway.stopOnce();
```

// 只拉曲线，不动手环开关
SleepGateway.fetch().run(result -> ui.render(result));

// 只开灯（给调试用）
SleepGateway.task(new LedOnTask()).run(...);
```

### 5.2 流程调用：把原子操作串成任意流程

```java
SleepFlow flow = SleepFlow.of(
        new ConnectTask(),
        new LedOnTask(Duration.ofSeconds(cfg.monitorSec())),
        new AwaitBoundaryTask(),           // 跨过整点结算时刻
        new LedOffTask(),
        new FetchPulseTask(Duration.ofSeconds(12)),
        new JudgeTask(),
        new PublishTask(),                 // 广播 + state.txt + verdict.log
        new AutoLockTask()                 // 只在 Transition.FELL_ASLEEP 时执行
);
flow.run();
```

内置三条流程复用同一批 Task：

| 流程 | 组合 |
|---|---|
| `GuardFlow` | `loop { plan → await → (LedOn → Await → LedOff → Fetch → Judge → Publish → maybe AutoLock) }` |
| `OnceFlow` | `LedOn(monitorSec) → Fetch → Judge → Publish`（不锁屏，除非配置允许） |
| `FetchFlow` | `Fetch → Publish(仅曲线)` |

### 5.3 外部调用：Intent / 广播（保持 Tasker 契约不变）

对外契约**冻结**，只增加不改：

| 入口 | 现有 | 重构后 |
|---|---|---|
| `com.vlab.autosleeper.action.START / STOP` | ✔ | 保持 |
| `...ONCE / FETCH / RESTART` | ✔ | 保持（RESTART 语义修正为"先终止再启动"） |
| `ASLEEP / AWAKE / STATE` 广播 + extra `bpm/score/asleep/at` | ✔ | 保持，`at` 改为 `capturedAt` 的同时保留旧 key |
| `state.txt` 格式 `asleep,62,80,1699...` | ✔ | 保持 |
| **新增** `...ACTION_TASK` + extra `task=LedOn\|FetchPulse\|Judge` | — | 新增，供脚本单独调原子操作 |

---

## 6. 参数映射表（旧 → 新）

| 旧 key | 新 Key | 变化 |
|---|---|---|
| `addr` | `ADDR` | 同 |
| `interval` | `INTERVAL_MIN` | 同 |
| `monitor` | `MONITOR_SEC` | 范围统一 `120..600`（修 P10） |
| `threshold` | `THRESHOLD` | 同 |
| `hold` | `HOLD_SLOTS` | 单位从"分钟"改"格"，默认 20 分钟 → 2 格；读旧值时自动 ÷10 迁移 |
| `rounds`/`windows` | `CONFIRM` | 两个键合并为一个，迁移时取 `needRounds` |
| `windowSlots` | 由 `HOLD_SLOTS` 取代 | 基线模式的窗口也用同一字段 |
| `judgeMode`(int) | `JUDGE`(enum) | 迁移映射 0→THRESHOLD 1→BASELINE |
| `measureMode`(int) | `MEASURE`(enum) | 0→PULSE 1→CONTINUOUS |
| `autoLock`/`resumeOnBoot`/`float*` | 同名 Key | 同 |
| `baseline` | 移入 `JudgeEngine` 的持久化快照 | 与判定状态一起存，避免"基线在、沉睡状态丢" |

迁移策略：`Config.migrate()` 在首次读取时检测旧 key 存在则换算写入新 key 并删除旧 key，一次性完成。

---

## 7. 分阶段落地

每个阶段独立可交付、可回滚，均以"真机跑通一轮"为验收。

### S0 安全网（不改行为）
- 抽出 `core/`（`PulseTable` / `Window` / `BoundaryClock` / `SlotMath`）纯 Java 类。
- 加 JVM 单测：环形取窗、跨天落盘区间、整点计划、迟到判定、两种策略在同一窗口下的输出。
- **验收**：单测全绿；真机拉一次曲线，新旧 `PulseTable` 输出逐格比对一致。
- 改动：+8 文件，`BandClient.PulseHistory` 改为委托 `PulseTable`（行为不变）。

### S1 BLE 层收口
- `BandSession` / `PulseCodec` / `BandCommand` / `BandError`。
- 修 P4（真实连接状态）、P12（死代码、失败原因、帧校验）。
- **验收**：单次测量、守护、手动拉取三条路径都能连上；拔掉手环电源后 `sleepUntil` 能提前结束并打日志。
- 改动：`BandClient` 删除，调用点 2 处替换。

### S2 判定层统一
- `JudgeEngine` / `SleepStrategy` / `ConsecutiveGate` / `Verdict` / `Transition`。
- 修 P2（统一 `Window`）、P3（字段语义）、P9（参数合并）、P13（窗口按时间而非按有效格）。
- **验收**：同一段 CSV 回放，两种模式都给出可解释的 `reason`；把 M 从 55 改到 50，兜底阈值跟着变。
- 风险控制：保留 `LegacyStrategy` 一版，日志里新旧判定**双跑对比**一周，确认无意外跳变后删除。

### S3 流程层
- `TaskRunner` / `Task` 族 / `GuardFlow` / `OnceFlow` / `FetchFlow` / `OpResult`。
- 修 P1（ONCE 按设置时长亮灯 + 支持手动提前结束）、P5（RESTART 先终止后启动）、P6（手动拉取严格只读）、P7（中断即退出）、P11（通知状态由 flow 统一设置）、P14（fetchWorker 纳入统一生命周期）。
- **验收**：`GuardService` ≤400 行（实测 350，只含生命周期/路由/通知）；连续点 5 次「测一次」不产生并发连接；守护中 RESTART 后进程只有 1 个采样线程（`adb shell ps -T` 验证）。
- 改动：`GuardService` 重写，其余文件不动。

### S4 数据与界面
- `ConfigSpec` / `ConfigBinder` / `StatusPresenter` / `PulseRepository` / `EventStore`。
- 修 P8（落盘区间）、P10（范围一致）、P15（版本单一来源）。
- **验收**：新增一个参数只需改 `ConfigSpec` 一行 + 布局一行；凌晨 00:10 拉取后 CSV 里同时有 `0,..` 与 `132..143`。

---

## 8. 真机回归清单

每阶段结束跑一遍：

1. 冷启动 → 授权蓝牙 → 点「立即测一次」→ 4 分钟内曲线末尾新增一格。
2. 点「拉取心率」→ 曲线刷新，手环 LED **不亮**，`staleRounds` 不变化（连续点 5 次验证）。
3. 开始守护 → 通知显示下次开灯时刻 → 到点开灯 → 跨整点关灯 → 曲线新增一格。
4. 守护中点「停止」→ 手环 LED 立刻熄灭且不再被点亮（P7）。
5. 守护中 `adb shell am broadcast -a ...RESTART` → 只有一个采样线程，无重复点灯。
6. 飞行模式/蓝牙关闭 → 各入口给出可区分的人话提示（不是统一"连接失败"）。
7. 跨天：23:50 拉一次、00:10 再拉一次 → 两份 CSV 都含 132..143 段。
8. 断网断电重启 → `BootReceiver` 恢复守护，基线从持久化快照恢复。
9. Tasker 侧：ASLEEP / AWAKE / STATE 三条事件都能收到，extra key 不变。
10. 连续跑 3 晚，`verdict.log` 无异常跳变（对比 LegacyStrategy 双跑结果）。

---

## 9. 已确认的决策

1. **「测一次」的语义（用户确认）**：点一下 → 发开灯 → **按界面上设置的亮灯时长跑** → 到点自动收尾；
   期间**允许手动点「结束」提前收尾**。两种收尾走同一条后续：关灯 → 拉曲线 → 判定 → 广播 →（按需）锁屏。
   已实现为 `OnceFlow`：到时正常收尾与手动提前收尾共用同一段代码，只有「等不等满」不同。
2. **四票打分保留**，降级为高级模式：默认阈值模式，勾选「基线打分模式」才启用。
3. **`PulseStore` 的公共静态字段一次性废掉**，改由 `PulseRepository` 单一持有（`slots`/`verdict`/`sleeping` 只走 publish 方法写）。
4. **判定的连续性双跑对比**仍建议在真机上做一轮：保留旧 APK（v1.19）与新 APK 各跑一晚，
   对比 `verdict.log` 的判睡时刻，确认没有提前/滞后突变。

---

## 10. 预期收益（量化）

| 指标 | 重构前 | 重构后 |
|---|---|---|
| 最长文件 | 674 行（GuardService 混杂六职责） | 505 行 MainActivity（纯界面）/ 350 行 GuardService（纯路由） |
| 「取最近 N 格」实现数 | 4 | 1（`PulseTable.window`） |
| 睡眠状态持有者 | 3（两个 judge + Service） | 1（`ConsecutiveGate`） |
| 配置定义处数 | 4（getter/setter/load/save） | 1（`ConfigSpec`） |
| 新增一个参数需改的地方 | 4 处 | 2 处（`ConfigSpec` 一行 + 布局一个控件，绑定表一行） |
| 可单测代码 | ~0 | `core/` 全量（15 个类无 Android 依赖） |
| 已知缺陷 P1–P15 | 15 项 | 全部处理（见下表） |

### 缺陷处理对照

| 编号 | 处理方式 |
|---|---|
| P1 ONCE 只亮 20 秒 | `OnceFlow` 按 `monitorSec` 跑，支持手动提前结束 |
| P2 取窗口 4 份实现 | `PulseTable.window` 唯一实现，空洞显式保留 |
| P3 借用 Result、sd 塞极差 | `Verdict` 独立类型，`sd` 就是标准差 |
| P4 `isConnected()` 假判断 | `BandSession.connected` 由回调真实维护 |
| P5 RESTART 双线程 | `FlowRuntime.stopGuard()` interrupt + join 后才允许重启 |
| P6 手动拉取改开关 | `FetchFlow` 只跑 `FetchPulse`，无 stale 逻辑 |
| P7 停止后仍点灯 | `awaitUntil` 中断即抛，`finally` 负责关灯 |
| P8 跨天不落盘 | 落盘写「所有有效格」，不再受 lastIndex 限制 |
| P9 三套连续次数 | 合并为 `holdSlots` + `confirmRounds`，兜底阈值跟随 M |
| P10 setter/getter 范围不一致 | clamp 只在 `Config` 一处做 |
| P11 通知文案卡住 | 通知状态由 flow 回调统一设置 |
| P12 BLE 遗留（死代码/无原因/硬编码下标） | `BandSession` 重写，失败带 `BandError`，解析收进 `PulseCodec` |
| P13 窗口按有效格而非时间 | `window(n)` 按格号连续取，空洞保留 |
| P14 fetch 线程未纳入生命周期 | 三条流程统一由 `FlowRuntime` 管理线程 |
| P15 版本号脱节 | `versionCode 18 / 2.0.0` |
