# 隐蔽性审计 / Anti-detection audit

**Scope**: everything this module leaves observable on a device to a *third party* — an app on the
same device, an app that receives a spoofed fix, a forensic tool with `adb`, and the user.
**Product requirement**: 隐蔽性 (the second half of task-7; the first half, 包体积, lives in
`docs/ROUND-NOTES.md`).
**Related**: `docs/CONTRACTS.md` (bridge protocol), `docs/E2E.md` (how the harness proves injection),
`hook/src/main/kotlin/com/immoral/location/hook/Fakes.kt` (what a spoofed fix looks like).

A module that fakes location cannot be invisible: it must change data that apps read. The point of
this document is that every remaining observable is **deliberate, known, and justified**, and that
nothing else (no tag, no key, no file, no extra) announces "a fake-location module runs here" when
it does not have to.

---

## 0. TL;DR

| # | 观测面 | 谁能看到 | 本轮处置 |
|---|---|---|---|
| S1 | `Location.getExtras()` 的 `immoral.*` 键（9 个）+ `satellites` | **任何拿到 fix 的 App** | **已改**：只由 `persist.immoral.extras=1` 打开，任何构建默认都不写（`Log.extrasEnabled()`），见 §3.2 |
| S2 | `Settings.Global` 键 `immoral_policy` | **任何 App**（`Settings.Global.getString` 无权限要求） | 只写不读的泄漏，建议删除（写入点在 `EngineRuntime.kt`，不在本轮写权限内）→ §6 D1 |
| S3 | `Settings.Global` 键 `immoral_cells` | **任何 App** | 保留：phone 进程与 system_server 之间唯一的状态通道（`TelephonyHooks.kt` 读）→ §6 D2 |
| S4 | logcat / LSPosed 日志里的 tag `[ImmoralLocation]` 和 187 个日志点 | adb / root / LSPosed UI（**普通 App 读不到别人的 logcat**，Android 4.1+） | **已改**：`Log.kt` 分级，release 默认安静，只留错误行 |
| S5 | `/data/local/tmp/libimmoralhook-*.so`（退化路径的 .so 落盘） | 任何 App（知道文件名即可 `exists()`/读取；目录不可列举） | 记录为已接受的退化路径 → §4 R1 |
| S6 | 模块包名 / 标签 / 签名 / 权限 出现在已安装应用列表 | **任何 App**（PackageManager，无需特殊权限即可枚举已安装包） | 无法移除（安装即存在）；靠 S1–S4 不提供**行为**指纹 → §4 R2 |
| S7 | LSPosed 作用域里出现模块 | 只有 root / LSPosed UI | 保留（框架的模型就这样）→ §4 R3 |
| S8 | 模块私有目录 `fw_status.json`、osmdroid 瓦片缓存、SharedPreferences | 只有 root（`/data/data/<pkg>`） | 保留（模块自身 UI 的状态来源）→ §4 R4 |
| S9 | 注入 `Location` 的字段形状（`isFromMockProvider=false`、provider、精度组合） | **任何拿到 fix 的 App** | 保留，并逐条解释为什么伪装成这个样子 → §4 R5 |
| S10 | 合成 `GnssStatus`（固定 SVID/CN0）与合成 NMEA | **任何 App**（`GnssStatus.Callback` / `OnNmeaMessageListener`） | 保留，标注可被"过于规律"识别 → §4 R6 |
| S11 | 合成 `ScanResult`（BSSID/SSID 由位置派生） | **任何 App**（`WifiManager.getScanResults`） | 保留（本来就要伪造）→ §4 R7 |
| S12 | 合成 `CellInfo`（mcc/mnc/lac/cid） | **任何 App**（`TelephonyManager.getAllCellInfo`） | 保留；同时在 S3 里因此暴露 → §4 R8 |
| S13 | `system_server` 内的 native hook（shadowhook 改写 ART 指令） | 只有 root / 反作弊（读 `/proc/<pid>/maps`、内存校验） | 保留（架构核心）→ §4 R9 |
| S14 | 前台服务通知 / 悬浮摇杆窗口 | 用户（可见于通知栏/屏幕） | 保留，是产品功能，且不是 App 可编程读取的面 → §4 R10 |
| S15 | 桥接收发用的动态 `BroadcastReceiver`（签名级权限保护） | root / `dumpsys` 可见；普通 App 只能**发**（会被签名权限拒绝） | 保留 → §4 R11 |
| S16 | 目标 App 进程本身被 hook | — | **不存在**：framework-only 设计，目标进程没有任何注入 |

"任何 App" 的定义与依据见 §1；每条观测量的复现命令见 §5。

---

## 1. 威胁模型 —— 谁是"观测者"

| 代号 | 观测者 | 能力 |
|---|---|---|
| **O1** | 拿到 fix 的目标 App（被作用域覆盖或独立模式下的白名单 App） | 读 `Location`、`GnssStatus`、`ScanResult`、`CellInfo`，读自身 logcat（**只能读自己 uid 的日志**） |
| **O2** | 同一台设备上任意普通 App（没有 fix） | PackageManager 枚举、`Settings.*` 读、stat 已知路径、起自己的进程 |
| **O3** | `adb shell` / root / 取证工具 | 全量 logcat、`/proc/<pid>/maps`、`/data/local/tmp/*`、`Settings`、LSPosed 配置库 |
| **O4** | 用户 / LSPosed 管理器 | 通知、悬浮窗、模块列表与日志 |

**关键结论（决定了 §3 的取舍）**：普通 App **读不到**别的进程的 logcat —— 从 Android 4.1 起
`READ_LOGS` 是 `signature|development` 权限，`logcat` 只返回调用者自己 uid 的行。所以 S4 的真正
受害者是 **O3**（取证）与 **O4**（用户），不是 O1/O2。这也是为什么本轮"生产环境安静日志"是按
*降低取证指纹* 而不是 *防同机 App* 来设计的。

反过来，S1/S2/S3 的受害者是 **O1/O2**：它们是任何未被特殊处理的 App 用公开 API 就能读到的数据，
因此优先级最高。

---

## 2. 逐条明细

### S1 — 注入 `Location` 的 extras（最高危）
* **是什么**：`Fakes.location(provider, snap)` 给每个伪造 fix 装一个 extras 包，键为
  `satellites` 加 9 个 `immoral.*`：
  `immoral.steps / cadence / stepSim / gpsSignalSim / cellSim / wifiSim / independent /
  independentPackages / ts`（`Fakes.kt:48-62`，常量在 `Fakes.kt:71-81`；非 snap 构造函数也会写
  `satellites` + `immoral.steps`，`Fakes.kt:117-118`）。
* **谁能看到**：**O1 和 O2 都可以** —— 任何拿到一个 fix 的 App 调
  `location.getExtras()` 就看到了。不需要权限、不需要 root。
* **为什么危险**：键名里直接写着 `immoral`（模块名），并且 `immoral.independentPackages`
  还带着白名单包名列表 —— 被观察的 App 不但知道自己在被伪造，还能知道**哪些别的 App 在被伪造**。
  这是一条"读了就等于确诊"的指纹。
* **处置（本轮已落地）**：改为受 `Log.extrasEnabled()` 控制（`Log.kt`）：
  **默认不写，任何构建都一样**；只有 `persist.immoral.extras=1` 时才写，且此时 key 名与值类型与
  改动前完全一致（e2e 的口径不变）。细节见 §3.2。

### S2 — `Settings.Global.immoral_policy`
* **是什么**：`EngineRuntime.publishPolicy()` 把 `<0|1>|<逗号分隔包名>` 写进
  `Settings.Global` 的 `immoral_policy`（`EngineRuntime.kt:450-462`，写入在 457）。
* **谁能看到**：**O2** —— `Settings.Global.getString(cr, "immoral_policy")` 是公开 API，普通 App
  无权限要求即可读。
* **现状（重要）**：**全仓库没有任何读者**（grep 结论：只有写入点 + 注释）。它原本是给被删掉的
  client 层的"状态总线"，现在是一条纯粹的多余指纹。
* **处置**：本轮 **不删**（写入点在 `EngineRuntime.kt`，不在 size-stealth 的写白名单内），
  作为 **D1** 交接给 Lead：删掉 `publishPolicy()` 的 `putString` 一行即可，功能零影响。

### S3 — `Settings.Global.immoral_cells`
* **是什么**：`EngineRuntime.publishCells()` 写入 `<0|1>|<type,mcc,mnc,lac,cid,pci,level,dbm,reg;…>`
  （`EngineRuntime.kt:473-509`），`TelephonyHooks.kt:160` 每 500 ms 读取一次。
* **谁能看到**：**O2**（同上，公开 API 可读）。
* **为什么留着**：`com.android.phone` 进程（`PhoneInterfaceManager.getAllCellInfo` 的家，uid 1001）
  里没有引擎；`system_server` 的 `TelephonyRegistry` 又没有 cell **取数**方法
  （见 `docs/ROUND-NOTES.md` §6.1）。没有这条通道，6-cells 永远只能是"hook 装上但不生效"。
* **处置**：保留（**R8/D2**），并在 §6 记录升级方案：只在 `cellSimulation=true` 时写，
  `false` 时写空串 —— 现在也是这么做的，但键名本身仍暴露"这套模块存在"。

### S4 — logcat / LSPosed 日志里的 `[ImmoralLocation]` (revised 2026-11-02: logs are ON by default at the owner's request; logcat is adb/root-only, so this is not an O1/O2 leak)
* **是什么**：`Log.TAG = "[ImmoralLocation]"`，全部经过
  `XposedBridge.log()`（`Log.kt:37`），于是同时出现在 LSPosed 的日志和 logcat（tag `Xposed`）。
  代码里共 **187 个**日志调用点：`LocationHooks 55 / Fakes 48 / GnssHooks 31 / BridgeServer 28 /
  EngineRuntime 25 / WifiHooks 22 / CommandChannel 18 / TelephonyHooks 10 / Refl 9 /
  XposedEntry 7 / FrameworkInstaller 6 / NativeBridge 3`。
  实测三次抓取共 **1 315** 行 `[ImmoralLocation]`：`hook …` 782 行、`==== … ====` 汇总 150 行、
  `initZygote …` 27 行，其余是调用期细节行（`WifiManager#getScanResults`、`getCellLocation`、
  `Fakes: …` 等）。安装横幅本身就是一份"我 hook 了哪些方法"的清单。
* **谁能看到**：**O3**（adb/root）、**O4**（LSPosed 日志面板）。**O1/O2 读不到**（§1）。
* **日志里没有坐标**：抓到的 1 315 行里，带 `lat=/lon=/latitude` 的行数为 **0**；SSID/BSSID
  在 wifi 路径上被 redact（`wifi identity redacted`，`e2e/lib/checks_ui.ps1:1133` 依赖这一行）。
* **处置（本轮已改）**：`Log.kt` 引入分级，见 §3.1。release 默认只剩错误行。

### S5 — `/data/local/tmp/libimmoralhook-*.so`
* **是什么**：`NativeBridge.extractFromModuleApk()` 把 APK 里的
  `lib/<abi>/libimmoralhook.so` 解到 `tempDir()`（`/data/local/tmp`），**显式设为 world-readable
  + world-executable**（`NativeBridge.kt:140-157`、`164-173`）。这是"直接 `System.load()` APK 内
  .so 失败"时的退化路径。
* **谁能看到**：**O2 部分可见** —— `/data/local/tmp` 目录是 `drwxrwx--x shell shell`，普通 App
  不能 `listFiles()`，但**知道文件名就能 `File.exists()` / `open` 读取**；文件名可猜
  （`libimmoralhook-arm64-v8a.so`）。读到后被观察者能直接看到模块符号名。
* **处置**：保留（**R1**）。原因：`jniLibs.useLegacyPackaging = true` + `extractNativeLibs="true"`
  的设计要求 system_server 能 `System.load()` 一个真实文件；改成 `memfd`/`/dev/shm` 属于架构级
  改动，且当前主路径是从模块 APK 的 `lib/<abi>/` 直接加载（`moduleLibDirCandidates()` 先试它），
  退化路径只在非常规 ROM 上才触发。**建议（D3）**：退化路径改为先写到 `app_zygote` 可访问但
  非全局可读的目录，并按随机后缀命名。

### S6 — 模块包名 / 标签 / 权限 出现在已安装应用列表
* **是什么**：`io.github.twtwilight.immorallocation`（debug 版加 `.debug`）、`app_name` 标签、
  签名证书、声明的权限（含 `QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`）。
* **谁能看到**：**O2** —— `PackageManager.getInstalledPackages()` 对普通 App 开放（Android 11+
  需要 `<queries>` 或 `QUERY_ALL_PACKAGES`，而后者本身是很多 App 都有的权限）。
* **处置**：无法移除（App 装了就存在，**R2**）。降低危害的做法是让它**只**是一个"装了但什么都没
  发生的 App"：这正是 S1–S4 本轮收紧的原因。**D4**（可选，超出本轮）：把 `app_name` 与图标做得
  更中性，并去掉 `xposeddescription` 里"fake location"字样（后者只有 LSPosed/root 可读）。

### S7 — LSPosed 作用域里出现模块
* **是什么**：`AndroidManifest.xml` 的 `xposedscope=android`、`xposedmodule=true`、
  `xposeddescription=…`。
* **谁能看到**：**O3/O4**（LSPosed 的数据库与 UI）。普通 App 读不到别的包的 `meta-data`。
* **处置**：保留（**R3**）。作用域 `android`（即 `system_server`）+ `com.android.phone` 是本模块的
  全部安装目标，无法再缩小。

### S8 — 模块自己写的文件
* **是什么・谁能看到**：
  | 路径 | 内容 | 可见性 |
  |---|---|---|
  | `/data/data/<module>/files/fw_status.json` | `running/lat/lon/speed/steps/ts/moduleVersion`（`CommandChannel.kt:260-299`） | 只有 root（私有目录；system_server 侧当前被 DAC 拒绝，见 ROUND-NOTES §6 的 `EACCES`） |
  | `/data/data/<module>/files/osmdroid/tile/**` | 瓦片缓存（`TileSources.kt:28-31`） | 只有 root |
  | `/data/data/<module>/shared_prefs/*` | 模块配置（`SettingsStore`） | 只有 root |
  | `/data/local/tmp/libimmoralhook-*.so` | 见 S5 | **知道路径即可读** |
* **处置**：私有目录部分保留（**R4**）；`/data/local/tmp` 部分见 S5/D3。

### S9 — 注入 `Location` 的字段形状
* **是什么**（`Fakes.kt:95-135`）：`provider` 原样透传（`gps/network/fused/passive`）、
  `time = 当前墙钟`、`elapsedRealtimeNanos = 当前 elapsedRealtime`、`accuracy` 按 provider 分档、
  `setVerticalAccuracyMeters / setSpeedAccuracyMetersPerSecond / setBearingAccuracyDegrees` 都填了
  合理值、`isFromMockProvider=false`、`setMock(false)`（若 ROM 有该方法）。
* **谁能看到**：**O1/O2**。
* **为什么这样**：`isFromMockProvider=true` 会让大量 App 直接判定"假定位"（这是最经典的检测面），
  所以必须压成 `false`；而精度族字段填齐，是为了不出现"只有 accuracy 有值、其余为 0"的
  不自然组合。
* **处置**：保留（**R5**）。**已知残余**：同一 provider 连续 fix 的 `time` 与
  `elapsedRealtimeNanos` 差值恒等于引擎 tick 间隔（固定 `updateIntervalMs`），一个耐心的 App 可以
  用时序规律性识别 —— 属于"引擎行为"，本轮不改（改会动到 §18 更新间隔的验收）。

### S10 — 合成 `GnssStatus` 与合成 NMEA
* **是什么**：`Fakes.gnssStatus()` / `mutateStatus()` 用卫星列表构造或原地改写 `GnssStatus`；
  NMEA 由引擎生成（`nmeaLines`）。
* **谁能看到**：**O1**（`GnssStatus.Callback` / `OnNmeaMessageListener`）。
* **残余风险**：SVID 集合、CN0、仰角/方位角是**固定模式**（`Fakes.kt:313-315` 只用于探针，
  实网数据来自引擎），一个 App 连续采样即可看出"星座分布不变"。**R6**，不在本轮改。

### S11 — 合成 `ScanResult`
* **是什么**：`Fakes.scanResults()` 用 `SimulatedAp` 造 `ScanResult`；`BSSID/SSID/level/frequency`
  由位置派生。
* **谁能看到**：**O1**（`WifiManager.getScanResults`）。
* **处置**：保留（**R7**）；capabilities 固定为 `[WPA2-PSK-CCMP][ESS]`（`Fakes.kt` 的
  `CAPABILITIES`），同样属于"过于统一"的残余信号。

### S12 — 合成 `CellInfo`
* **是什么**：`Fakes.cellInfos()` 造 `CellInfoLte/Gsm`（mcc/mnc/lac/cid/pci/level/dbm）。
* **谁能看到**：**O1**；并且因为 S3，**O2** 也能从 `Settings.Global.immoral_cells` 读到同一份值。
* **处置**：保留（**R8**）。

#### S12 补充：`6-cells` 真实性边界（2026-11-02，`core/.../CellSynth.kt`）

`CellSynth` 现在按**嵌套的位置网格**推导标识符，而不是"每个位置抽一串随机数"：运营商区域网格 **0.5°（≈55 km）**
决定 PLMN，跟踪区网格 **0.02°（≈2.2 km）** 决定 TAC，eNodeB 网格 **0.002°（≈220 m）** 决定 ECI ——
于是"站着不动 → 逐字节相同、走 200 m → 同 TAC 内切换（换 ECI）、走过几公里 → 换跟踪区"，行为像一张真实的无线网，
而不是每次读数都在抖。可验证的一致性：PLMN 全部取自真实已分配的 MCC/MNC 对（中国 460/00、460/01、460/11，
境外仅 310/311、234、262、440、505 这几张极小的表），TAC 是 16 位且在**该运营商的取值带内**，
ECI 遵循 3GPP TS 36.413 的 `eNodeB id(20bit) << 8 | sector(1..255)`，PCI 在 `0..503` 且同一份列表内互不相同，
服务小区已注册、信号强于任何邻区。**边界（写下来而不是暗示）**：这是"**看起来合理的元组**"，不是目标点附近
**真实基站**的查询 —— 没有公开基站数据库，"邻区"是相邻网格里的合成 eNodeB；`CellIdentityLte` 里我们**不写
EARFCN/频段**（结构体只填 mcc/mnc/tac/ci/pci），所以"频段一致性"这条目前无从判断也无从伪造；
中国的 TAC 取值带是**为了自洽而划定的**（不存在公开的"运营商↔TAC 区间"表，编一份看起来权威的数字比承认更糟）；
境外位置只在上面五张表覆盖的国家内选真实 PLMN，其余地区会从这五张表里挑一个**真实但未必属于该国家**的对子。
所以：能骗过"范围/结构/稳定性"类的检查，**骗不过**与真实网络日志（或真实基站数据库）的比对 —— 这正是
FakeLocation 那类"搜索附近真实基站"卖点所在，也是本模块**明确不做**的部分。

### S13 — `system_server` 内的 native hook
* **是什么**：`libimmoralhook.so` 用 shadowhook 在 `system_server` 里改写 ART 指令（sensor 层）。
* **谁能看到**：**O3**（`/proc/<pid>/maps` 里出现 `libimmoralhook.so`；内存校验）、反作弊 SDK
  在**自己进程**里能看到的只有"数据被改写"这一后果，看不到 hook 本身。
* **处置**：保留（**R9**）——这是本模块能骗过 `SensorManager` 的唯一手段。

### S14 — 前台服务通知与悬浮摇杆
* **谁能看到**：用户（通知栏、屏幕上）。**不是** App 可编程读取的面。
* **处置**：保留（**R10**，产品功能）。

### S15 — 桥接收发（动态广播）
* **是什么**：`BridgeServer` 在 `system_server` 里 `registerReceiver(..., Bridge.PERMISSION_CONTROL, …)`
  （`BridgeServer.kt:253-292`），权限在模块 APK 里声明为 `signature`。
* **谁能看到**：**O3**（`dumpsys activity broadcasts`）；普通 App 可以猜 action 并 `sendBroadcast`，
  但会被签名级权限拒绝，且**不能**收到回复（回复走显式组件）。
* **处置**：保留（**R11**）。

### S16 — 目标 App 进程本身
* **现状**：**没有任何注入**。`XposedEntry` 只对 `system_server`/`android`/`system` 和
  `com.android.phone` 安装 hook（`XposedEntry.kt:31-60`），任何其他进程"process 里没有我们的
  class loader、没有改写过的方法"。这是本模块最重要的一条隐蔽性资产：目标 App 自查
  `ClassLoader`、方法体校验、`/proc/self/maps` 都不会看到我们。

---

## 3. 本轮改动

### 3.1 日志分级（已落地，`hook/.../Log.kt`）

原先 release 与 debug 输出完全相同。现在 `Log` 有两个级别：

| 级别 | 方法 | release 默认 | 理由 |
|---|---|---|---|
| detail | `i / w / once / rate / hook / summary / dump` | **关** | 这是指纹本体：安装横幅列出被 hook 的方法名，调用期行按引擎频率刷 |
| error | `e` | **开** | 生产安装仍要可诊断；调用方用 `once` 去重，不会刷屏 |

打开方式（任一）：
1. `debug.immoral.log=1`（root/adb `setprop`，属性名 `Log.kt:40`）；
2. 模块 APK 来自 `.debug` 构建 —— 由 `ModuleInfo.modulePath` 的包目录后缀判定
   （`Log.kt:89-94`，实测路径形如
   `/data/app/~~<hash>==/io.github.twtwilight.immorallocation.debug-<hash>==/base.apk`）。

**为什么按"构建类型"而不是按"App 是否在前台"**：e2e 矩阵（`e2e/install.ps1`）装的就是
`app-debug.apk`，所以 **e2e 的日志证据完全不受影响**（零改动）；而发布产物 `app-release.apk`
默认安静。这样"生产安静"和"可验收"不再冲突。

**为什么 hook 模块不能用 BuildConfig**：`hook/build.gradle.kts` 里 `buildConfig = false`，且本轮
写权限只有 `Log.kt`；用 APK 路径判定不需要改构建配置，也不会因为包名改名而失效
（路径里取的是包目录名，`endsWith(".debug")`）。

**测量**：改动只影响"是否写日志"，不影响任何 hook 决策 —— 编译验证
`.\gradlew.bat --offline :hook:compileReleaseKotlin` 通过；用 debug 构建跑 e2e 时日志与改动前逐行
同构（同一批 `hook OK` / `==== ... ====` 行）。

### 3.2 `immoral.*` extras 门控（**已落地**，`hook/.../Fakes.kt` + `Log.kt`）

两处 extras 写入（`Fakes.kt:44-70` 的 snap 版本与 `Fakes.kt:117-125` 的普通版本）现在整块包在
`if (Log.extrasEnabled())` 里：

```kotlin
fun extrasEnabled(): Boolean {              // Log.kt
    extrasMemo?.let { return it }
    val enabled = flagOf("persist.immoral.extras") ?: false   // 默认 OFF（任何构建）
    extrasMemo = enabled
    rate("extras.mode", 60_000L, if (enabled) "location extras: immoral.* INCLUDED (…=1)"
                                 else "location extras: immoral.* OMITTED (production default; …)")
    return enabled
}
```

* **默认关闭，与构建类型无关**：`persist.immoral.extras` 未设置即不写。key 名与值类型在打开时与改动前
  **逐字节一致**（`Fakes.kt:71-81` 的常量未动），只是不再出现在生产 fix 里。
* **关闭时是"没有 bundle"**，不是"空值"：`location.extras` 保持 `null`，不会被留一个空 `Bundle`。
* **一条模式自述行**：`rate("extras.mode", …)` 在首次判定时输出一次（detail 级别），内容只说
  `INCLUDED`/`OMITTED` 与属性名，不含任何坐标或包名；因此"这次跑的是哪种模式"可以直接从日志读出。
* **e2e 契约**：矩阵现在由 `e2e/install.ps1` 设置 `persist.immoral.extras=1`（Lead 负责该改动与
  `docs/E2E.md` 的一行说明）。不设置时，任何读 extras 的断言都会读到 `null` —— 这是**故意**的：
  "读不到"正是生产环境的正确表现。
* **操作顺序很重要**：判定结果在**每个进程内只算一次**（`extrasMemo`）。`system_server` 一旦起来就缓存了，
  所以 `setprop persist.immoral.extras 1` 必须发生在**激活模块的那次 reboot 之前**（或改完再重启一次），
  否则那一轮 extras 仍然是空的。同理适用于 `debug.immoral.log`。
* **不影响产品功能**：`app/**` 与 `core/**` 中没有任何代码读取这些 extras（grep 0 命中）；旧的 client
  状态总线已随 client 层一起删除。

---

## 4. 保留项与理由（accepted risks）

| ID | 保留的东西 | 为什么必须保留 | 残余风险 |
|---|---|---|---|
| **R1** | `/data/local/tmp` 落盘（S5） | 退化路径要一个"system_server 能 exec 的真实文件" | 知道文件名的 App 可探测；建议 D3 |
| **R2** | 模块包名可见（S6） | 安装即存在 | 任何 App 都能看到"装了这个 App"，但看不到它在工作 |
| **R3** | LSPosed 作用域（S7） | 框架机制 | 仅 root/UI 可见 |
| **R4** | 模块私有文件（S8） | UI 状态来源 | 仅 root 可见 |
| **R5** | `Location` 字段形状（S9） | 压掉 `isFromMockProvider` 是核心目标 | 时序规律性可被耐心 App 识别 |
| **R6** | 固定星座/NMEA（S10） | 引擎按位置生成 | 采样可见"过于规律" |
| **R7** | 合成 AP（S11） | 功能本体 | capabilities 过于统一 |
| **R8** | 合成 cell（S12） | 功能本体 | 与 S3 的通道叠加暴露 |
| **R9** | native hook（S13） | 骗过 sensor 的唯一手段 | 仅 root/反作弊可见 |
| **R10** | 通知/悬浮窗（S14） | 产品功能 | 用户可见（预期内） |
| **R11** | 广播桥（S15） | 控制通路 | root 可见 action |

### 4.1 已核实的事实：应用内地图就是高德（并由此得到本轮唯一的资产裁剪）

这条不是"隐蔽性"而是"前提真伪"，但它决定了 包体积 的取舍，且推翻了流传中的说法，所以记在这里。

* `e2e/lib/checks_ui.ps1:1316` 原话是"产品跑在 osmdroid 栅格兜底上，高德 key 是故意留的 stub"。
  **实测为假**：`ReadyAMapSDK v1.0.4` + `3dmap 10.0.600` 用 AAR 自带 key 就能初始化出图，
  应用进程日志里有 `I AmapHost: amap map loaded (tiles rendered)`，截图是高德 样式地图。
  osmdroid 只是**初始化失败时**的兜底（S11/S13 的 osmdroid 相关结论仍然成立，只是它不是默认路径）。
* 由此做的 A/B（同一设备、冷启动；每臂 uninstall → install → 20 s → 截图 + 应用进程 logcat）。
  第一轮（资产全留的控制组基础上）：`cfg` 可去、`ae` 黑屏、六组全丢黑屏。
  第二轮（2026-11-02 上午，**一个 APK 只改一个变量**，基线 = 已丢 `cfg` 的发布配置）：

  | arm | APK 字节 | MiB | Δ | 截图 | `AmapHost: amap map loaded` | 结论 |
  |---|---|---|---|---|---|---|
  | 控制（发布配置） | 6 555 623 | 6.25 | — | 955 530 B | 有 | 参照 |
  | **丢 `map_assets`** | 5 953 561 | 5.68 | −602 062 | 190 877 B | **有（！）** | **拒绝：地图空白** |
  | 丢 `map_custom` | 6 471 563 | 6.17 | −84 060 | 954 033 B | 有 | 接受 |
  | 丢 `offlinemapv4.png` + `amap_resource1_0_0.png` | 6 312 872 | 6.02 | −242 751 | 1 445 606 B | 有 | 接受 |
  | **三组一起（= 现在的发布配置）** | **6 229 074** | **5.94** | −326 549 | 1 445 518 B | 有 | 与对照同视口逐像素一致 |
  | （第一轮）丢 `ae` | 7 827 484 | 7.46 | −338 281 | 130 276 B | 有（很晚） | **黑屏**：`FileNotFoundException: ae/res.zip` → `NullPointerException … GLMapState.setMapZoomer` |
  | （第一轮）六组全丢 | 5 274 854 | 5.03 | −2 890 866 | 130 197 B | 无 | **黑屏** |

  同视口对照：`pair-control1.png`（控制 1 446 140 B）与 `pair-shipping1.png`（发布 5.94 MiB 1 445 202 B）
  是同一张图 —— 视野、标注、POI 图标、样式、标记、chrome 全同，只有状态栏时钟不同。

* **落地**：release 默认丢弃 `assets/cfg` + `assets/map_custom` + `offlinemapv4.png` +
  `amap_resource1_0_0.png`（aapt2 `--ignore-assets`，Variant API 区分 debug）；`assets/ae` 与
  `assets/map_assets` **必须留**。`-PimmoralAmapAssets=true` 可全部放回。7.78 → **5.94 MiB**。
* **教训（比数字更重要）**：`map_assets` 那一臂**打印了 `amap map loaded` 却画出空白面** ——
  所以"日志行"不能当渲染证据，**必须把帧读回来看**。资产类改动一律以帧为准。
* **`map_custom` / 两个 png 接受的双重依据**：帧不变，且 `app/`+`core/` 里没有任何
  `OfflineMap*` / particle / fog / `CustomMapStyleOptions` 调用（grep 0 命中）。残余风险：若将来启用
  粒子/天气图层或离线地图占位 UI，这两类资源缺失会露馅 —— 目前产品从不启用它们。
* **证据边界**：所有臂都是同一个后端（高德）、同一台设备/ROM、冷启动。结论仅限
  "**出厂配置下**用户看不出差别"；不覆盖室内地图（`cfg/idrres` 已丢）、其它 ROM、真实 key、
  后续 高德 SDK 版本。另外实测**装/卸之间地图视野会漂**（同一控制 APK 前后两帧分别落在上海/北京），
  所以跨臂比较只能靠"同视口的帧 + 目视读回"，不能靠帧字节数。

---

## 5. 复现 / 验证

```powershell
# S1: 任何 App 都能读到的 extras（用模块自己的 debug 构建做注入源）
adb shell "dumpsys location" | Select-String -Pattern "last location"
# 或在目标 App 里: location.extras?.keySet()

# S2/S3: 无权限读取 Settings.Global
adb shell settings get global immoral_policy
adb shell settings get global immoral_cells

# S4: 日志指纹（release 构建应为空 / 只有 E 行；debug 构建照旧）
adb logcat -d | Select-String -Pattern "\[ImmoralLocation\]"
adb shell setprop debug.immoral.log 1     # 打开 detail
adb shell setprop debug.immoral.log 0     # 关掉

# S1 门控专用开关
adb shell setprop persist.immoral.extras 0  # 强制不写 immoral.* extras
adb shell getprop debug.immoral.log

# S5: 落盘路径（普通 App 不能列举目录，但可以 stat 已知文件名）
adb shell ls -l /data/local/tmp | Select-String -Pattern "immoralhook"
```

---

## 6. 建议交接给 Lead 的后续项

| ID | 内容 | 在哪改 | 影响 |
|---|---|---|---|
| **D1** | 删除 `Settings.Global.immoral_policy` 的写入（**全仓库无读者**） | `hook/.../EngineRuntime.kt:450-462` | 零功能影响，直接去掉一个 O2 可读指纹 |
| **D2** | `immoral_cells` 改为"仅在 `cellSimulation=true` 时存在"，关闭时 `remove` 而不是写空串 | `EngineRuntime.kt:473-509` | 需要 6-cells 验收口径同步 |
| **D3** | 退化路径 .so 不再落在 `/data/local/tmp` 且用随机名/0700 | `hook/.../NativeBridge.kt:140-173` | 需要设备验证 system_server 的 exec 权限 |
| **D4** | 应用标签/图标中性化；去掉 `xposeddescription` 的 "fake location" 字样 | `AndroidManifest.xml` | 仅 O3/O4 可见面 |
| **D5** | `docs/CONTRACTS.md` / `docs/E2E.md` 写明 `immoral.*` extras 只存在于 debug 构建 | docs | 防止将来 e2e 跑 release 时"静默读到空 extras" |



## S13. 隐蔽性现状与一条由我自己引入的新暴露面（2026-11-02 17:20）

**已经做到的（可被帧/日志证明）**
- 伪造的 `Location` 结构完整：`time` 与 `elapsedRealtimeNanos` 单调、`accuracy`/`verticalAccuracy`/`speedAccuracy`/`bearingAccuracy` 齐全、
  `isFromMockProvider=false` 且调用了隐藏 `setMock(false)`（`Fakes.kt:100-122`），默认不写任何 `immoral.*` extras。
- 基站元组改为**从位置网格派生的合理值**（真实 PLMN 表、3GPP ECI 布局、PCI 唯一、服务小区最强），见 §S12。
- 原生库解压到随机命名 0700 私有目录，不再有可预测的 `/data/local/tmp/libimmoralhook-*.so`。

**明确不做（受"只能 hook framework"约束）**
- 不注入目标应用进程，因此**无法**对抗应用侧的"环境指纹/反 hook 扫描"（`ro.kernel.qemu`、Magisk 痕迹、`XposedDetector` 之类）。
  FakeLocation 把"反检测"当卖点，我们这条路做不到等价物——这是架构选择带来的能力边界，写在这里而不是含糊过去。

**新暴露面（我自己在上几轮引入的，必须记下）**
- `Settings.Global` 里的 `immoral_state` / `immoral_cells` 两个 key **任何应用都能读**，且名字直白。
  也就是说：任何 app 只要读一次 Settings.Global 就能确认"这台机器装了改位器"。这比任何 hook 检测都更容易。
- 现在只在引擎运行时写（停止后 `ts` 老化、app 侧忽略），但**写入期间是可读的**。
- 候选修法，按代价排序：① 改成中性 key 名（便宜、只降低显眼度）；② **只在自家 app 前台时写**（真正的修法，需要 system_server 侧知道
  顶层 activity，可用 `ActivityTaskManager` 的顶层包名判断）；③ 换成定向广播（仅我们的包收），代价是 app 侧要改成订阅。

**结论**：在 ①/② 落地前，这一条应当作为**已知限制**出现在 README 的"隐蔽性"一节，而不是等用户自己发现。

### S13.1 状态行暴露面已关闭（2026-11-02 18:05）

`immoral_state` 曾经是最廉价的指纹：任何应用读一次 `Settings.Global` 就能确认装了改位器。
现在 app 在自己的 `ON_START`/`ON_STOP` 边沿告诉框架"界面是否可见"，写入方在界面隐藏的那一刻**删除该行**，
回来时再写。设备证据（同一会话内）：

```
app 可见   → settings get global immoral_state = {"running":true,"lat":31.23661,…,"ts":…}
按 HOME 后 → settings get global immoral_state = null
```

配合此前"引擎停止即删行"，后台暴露已关闭；剩余窗口恰好是**我们自己的 activity 在屏幕上**的那段时间。

**仍然存在的**：`immoral_cells` 必须对 phone 进程随时可读（应用后台查询基站时也要模拟），
因此**不能**用同样的可见性门控。现状是"只有用户开启基站模拟时才存在"，且名字直白。
可选缓解：改中性 key 名（只降低显眼度），或改成只对 `com.android.phone` 可见的私有通道——
后者需要额外的跨进程机制，暂列后续。

### S13.2 可见性门控的副作用（记录下来，避免以后被当成回归）

状态行只在**自家 UI 可见**时发布，于是验收报告里会出现：
```
PASS 5-route … globalState(immoral_state): t1=unset t2=unset moved=False
```
这是**正确读数**——检查期间 app 被 force-stop / 重启，并不稳定处于前台，所以该行理应不存在。

三种状态已在本轮各自设备验证：可见+运行 → 实时 JSON；可见+空闲 → `null`；按 HOME 后 → `null`。
也就是说，这一行的存在现在**恰好**等价于"用户正在看我们的界面且引擎在跑"，而这也是它唯一被需要的时候。

**代价**：harness 里"用该行证明通道在动"的探针，只有在自家 activity 确实在前台的那一步才有效；
其他时候应读作"不可见 → 未发布"，而不是"通道坏了"。
