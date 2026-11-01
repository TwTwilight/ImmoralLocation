# 本轮进度与交接（2026-11-01）

> 给下一轮（或下一个人）的**冷启动说明**：先读这一页，再读 `docs/ACCEPTANCE.md`（投诉逐条结论）与
> `docs/UIUX-REWORK.md`（本轮 UI 契约）。

## 一句话状态

用户四条投诉里的 **①UI/UX、②摇杆方向、③高德稳定性、④包体积** 在本轮都已有设备证据；
剩下的是**补测**（几条自动化行被 harness/作用域问题挡住）与**打磨**，不是「没做完」。

| # | 投诉 | 状态 | 证据 |
|---|---|---|---|
| ① | UI/UX 太差、要 Material You | **完成** | `docs/screenshots/new-*.png`（9 张）+ `docs/ACCEPTANCE.md` §1/§7 |
| ② | 摇杆方向错 | **完成（实测）** | 真实拖拽 → `SetJoystick(0,1)` → 13.9 m/s → 纬度 +0.000318°（0° 误差）；自动化左向 277.3°（7.3° 误差） |
| ③ | 高德半分钟变回真实位置 | **修复并实测（framework-only）** | 180 s / 100 s / 65 s 三次冷启动全部停在模拟位置；`docs/AMAP-STABILITY.md` §6/§10 |
| ④ | 包体积 | **完成** | release **9.97 MB**（debug 47 MB），`23-module-integrity` / `24-apk-size` 已进 CI |
| — | 路径规划 / 途经点 / 模拟路径 | **完成** | `docs/ROUTING.md`；`15c` PASS；`data-source=高德路径` 实测 |

## 关键架构决定（别破坏）

1. **framework-only 是产品约束**（用户原话：「只勾 framework 不侵入目标 app」）。
   默认 LSPosed 作用域：`system` + 我们自己的 app + verifier；**不包含** 高德/百度/云运动。
   `hook/client/**` 保留为 ROM 差异的兜底，但**不是**默认路径，文档里已如此标注。
2. **framework 层按实例 hook**：`com.android.server.wifi.*` 由 APEX 装载，`handleLoadPackage` 时类加载器看不见，
   必须用 `ServiceManager.getService("wifi"/"wifiscanner")` 取实例再 `javaClass` hook
   （`hook/.../framework/WifiHooks.kt::hookLiveServices`，含 `addService` / 延迟重试兜底）。
3. **摇杆语义**：`SimulationConfig.followHeading` 默认 `false`（屏幕对齐，上=北）；
   跟随时摇杆刻度环按 `-heading` 旋转并显示 N。`bearing = base + atan2(x, y)`。
4. **测试 oracle**：判断「注入是否生效」用 **verifier 的 `VERIFY {"kind":"location"…}`**，
   不要用 `dumpsys location | grep immoral.`（只在刚投递过时存在，会产生假阴性）。

## 仍然开放（下一轮可做）

1. **③ 高德的成因**：现象已消失（当前构建与 pre-fix `490ea3b` 在 framework-only 作用域下都保持模拟位置 ≥2–3 分钟，
   含"高德刚定位到真实位置之后"），但**成因未隔离**，且已证明**与 framework wifi 实例 hook 无关**（高德从未调用它）。
   最大嫌疑是当时中间态工作树里的 **client 层 fail-closed**（`490ea3b` 没有这段）。下一步：三个单变量构建
   （只有 client fail-closed / 只有 wifi 实例 hook / 两者）把假设证伪或坐实，见 `docs/AMAP-STABILITY.md` §11/§12。
2. **被 BLOCKED 的自动化行**：`0/12/12b/15a/15b/18/25`。
   原因：harness 的合成手势偶发被 pad 当成 tap/scroll；修法是等地图稳定后再按下、
   并轮询 hook 日志里的 `decoded: SetJoystick` 再测（已在 `checks_ui.ps1` 头部写明）。
3. **UI 打磨**（非阻塞）：`lintDebug` 3 条 deprecated 图标；marker 精度光环的 1.5dp 描边只在 build 里、
   尚未截图；waypoint 明细卡片的空行间距。
4. **尚未做的产品点**：`反检测面板`（旧版也没有，属新功能）；地图底图自定义 Material 风格（AMap 自定义样式）。
5. **新一轮设备验收**：先 `e2e/activate_module.ps1 -Packages android,com.immoral.location.debug,com.immoral.location.verifier`，
   重启，再 `e2e/run_matrix.ps1`（约 10 分钟，含 3 分钟高德窗口）。

## 常用命令

```powershell
cd D:\ImmoralLocation
.\gradlew.bat --offline :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
.\e2e\install.ps1                     # 构建 + 安装 + 激活 + 重启
.\e2e\run_matrix.ps1                  # 全量验收（写 e2e/out/report.json）
.\e2e\run_matrix.ps1 -Only 14         # 只跑某项
.\e2e\capture_screens.ps1             # 9 张评审截图 → docs/screenshots/
.\hook\harness\amap_verify.ps1 -Seconds 180 -OutDir docs\amap-evidence\verify
.\e2e\lint.ps1                        # harness 自检（CI 也跑）
```

## 重要文件

- UI 契约 `docs/UIUX-REWORK.md`；验收表 `docs/ACCEPTANCE.md`；E2E 手册 `docs/E2E.md`
- 高德排查全过程 `docs/AMAP-STABILITY.md`（现象 / A-B / 已排除通道 / 根因 / 修复 / 复现）
- 路由设计 `docs/ROUTING.md`；工程契约 `docs/CONTRACTS.md`
- 本轮提交：`490ea3b`（路线端点）→ `f9e5f8b`（WiFi 脱敏 + UI 修）→ `98bb65e`（验收文档 + 截图）
  → `62184be` / `3c51257`（最终矩阵 + oracle 修正）→ `ad622f6`（framework wifi 实例 hook）

## 3. 纯 framework（client 层已删除）的设备验证（2026-11-01 19:53）

按要求删掉 client 层后，用**只有 `system` 作用域**的安装验证了一遍真实投递路径（不是看 `dumpsys`，而是让
verifier 真的注册监听器）：

```
# 引擎 STATIC 在天安门，verifier 冷启动 8 s，统计 onLocationChanged 的 provider/lat
grep -o '"provider":"[a-z]*","lat":[0-9.]*' | sort | uniq -c
     32 "provider":"fused","lat":39.9087
     19 "provider":"gps","lat":39.9087
     27 "provider":"network","lat":39.9087
     13 "provider":"passive","lat":39.9087
```

四个 provider **全部**返回模拟坐标，`mock:false`；hook 日志证明投递确实经过 framework 层：

```
delivery hook: LocationListenerRegistration#acceptLocationChange pkg=com.immoral.location.verifier (normal)
delivery hook: LocationListenerTransport#deliverOnLocationChanged pkg=com.immoral.location.verifier (normal)
```

⇒ 「不侵入目标 app」这件事是可以做到的：应用进程里没有任何 hook，拿到的却是模拟位置。

**已知问题（下一步）**：`e2e/run_matrix.ps1` 在同一构建上 12 项全 FAIL，报 "no location line at all"，
但同一时刻手写的 verifier 冷启动能拿到数据（上面那段）。差异在 harness 的 verifier 流启动/游标逻辑，
不是产品行为；下一轮先修 harness 再看矩阵数字。

## 4. UI → 引擎 全链路（framework-only，无任何 app 内 hook）2026-11-01 20:08

从界面真实操作（不是广播注入）：

1. 前台 App 点「开始」→ hook 日志 `command decoded: Start` / `SetPosition`；
2. 点摇杆 FAB 打开 152dp 摇杆 → `input swipe` 向上拖 5 s → `decoded: SetJoystick` 计数 134 → **454**；
3. `dumpsys location` 的注入点从 `31.230416` 变为 **`31.230866`**（纬度 +0.00045° ≈ 50 m，向正北）。

⇒ 用户视角的「点一下换位置 / 拖摇杆走」在纯 framework 下完全可用；应用进程里没有任何 hook。

**注意（MIUI 特性，写进 e2e 备忘）**：如果引擎是用 `am broadcast` 从外部拉起的、而我们的 App 没在前台/没起前台服务，
MIUI 的 freezer 会拒发 `ACTION_REPLY`（`Greezer Denial … need cached broadcast`），App 界面因此收不到状态、
摇杆也不会发命令。真实使用场景（从 App 里点开始，前台服务在跑）没有这个问题；用广播驱动做验收时，
必须先让 App 回到前台。

## 5. 删除 client 层后的功能盘点（2026-11-01 20:5x）——哪些还在、哪些要补

**仍然正常（framework-only 实测）**
- 位置注入：四个 provider（fused/gps/network/passive）全部投递模拟坐标，`mock=false`，应用进程零 hook。
- 摇杆/速度：引擎 tick 日志逐档精确 —— WALK 1.40 / RUN 3.30 / BIKE 5.50 / DRIVE 13.90 / PLANE 230.00 / CUSTOM 7.50 m/s，ratio 全 1.00；方位 0°。
- 快速切换：~1.8 s 到位。
- 模块完整性 / 包体积：release 9.95 MB，`xposed_init → XposedEntry`、5 个 hook 类仍在。

**必须补到 framework 层（client 层原本在应用进程里做，现在没有了）**
1. **基站模拟**（`6-cells`）：先把 `com.android.phone` 加回作用域（已改默认值）——`PhoneInterfaceManager` 在它里面，不加就永远不生效。
2. **WIFI 模拟**（`7-wifi`）：HyperOS 的 `WifiServiceImpl` **没有** `getScanResults*` 方法；已把匹配改成"返回 List 且名字含 scanresult/scanlist"，并在 miss 时打印候选方法名单，下一次 run 就能知道该 hook 谁。
3. **步频模拟**（`8-steps`）：原实现在应用进程（`SensorHooks`）。framework 侧应走 `SensorService` 原生 hook（`libimmoralhook.so`），需确认该路径在这台机器上真的注入。
4. **GPS 信号模拟**（`9-gnss`）：`gpsSignalSimulation=false` 时按设计"什么都不给"（§5.1 真值闸门）；**必须用 `=true` 的配置去测**合成卫星/NMEA 是否还在。
5. **独立模拟**（`10-independent`）：非白名单应用仍能看到假位置 ⇒ framework 的按注册投递过滤有洞（client 层以前掩盖了它）。这是真实 bug，优先级最高。

**方法学**：不要再用 verifier 的稀疏位置采样算速率（会得到 0 / 67.53 m/s 这种噪声）；用引擎自己的
`tick … speed=… moved=…` 或 `fw_status.json`（注意 `mtime ≤ 3.5 s` 才算新鲜）。

## 6. framework 合成缺口——实测结论（qa-e2e，2026-11-01 21:45，构建含 `ParceledListSlice` 修复）

**7-wifi：已修复 → PASS**（本次唯一一条从 FAIL 翻绿的）
```
hook: wifi#getScanResults -> 5 synthetic APs for uid 10274
check: A: 5 APs (first BSSID ca:a1:41:0e:7d:66); C: 6 APs; changed=True
```
根因是**返回值形状**而不是类名：AIDL 的 `getScanResults` 返回 `ParceledListSlice`，不是 `java.util.List`，
旧匹配器要求 `List::class.java.isAssignableFrom(returnType)` 所以永远 miss。**判据**：看
`hook OK … -> <ReturnType>` 与 AP 列表是否随位置变化；`wifiSimulation=true` 走的是"替换"分支，
`wifi.withheld` 本来就不会出现，别再拿它当成功条件。

**6-cells：仍 FAIL（framework 合成缺口）**
```
(system)            hook MISS com.android.phone.PhoneInterfaceManager (class not present in this process)
(system)            hook MISS com.android.server.TelephonyRegistry#getAllCellInfo (no matching method)
(system)            ==== framework/telephony: 0 hook targets resolved, 2 missed ====
(com.android.phone) ==== framework/telephony: 1 hook targets resolved, 1 missed ====
check: A ci=48356046/tac=22538/pci=302 -> C ci=48356046/tac=22538; mcc=46000 plmnOk=True
```

**6-cells 复测（22:00，构建 APK 21:46:12 含 telephony 结构化匹配）：当时方法名未拿到；**现已由 hook-loc 补全，见 §6.1**。
我最初的归因（"安装期日志没有落地 ⇒ 日志路由回归"）**过宽，已撤回**：我的 grep 模式里没有
`FrameworkInstaller.install()` 的第一行 `=== installing framework layer in process=… pid=… ===`，
而那是唯一能证明"装了没装"的行。仍成立的两条事实：(a) 那次 boot 的 lspd 日志里没有 telephony 安装行；
(b) 同一构建**调用期**日志正常（`wifi#getScanResults -> 5 synthetic APs for uid 10274`），
且 `-Only 6` 期间 hook 确实在跑（`config applied: … cell=true …`）。

**踩坑记录**：`run_matrix` 起手 `Clear-Logcat` 会**擦掉 boot 期的安装日志** —— 要读安装期日志必须在
reboot 之后、跑 matrix 之前读；另外 `framework/telephony: install() reached (uid=…)` 每 boot 会打 8 次，
`grep framework/telephony` 会被它淹没，应 grep `hook OK` 或 `==== framework/telephony: … ====` 汇总行。

### 6.1 6-cells 的真实结论（hook-loc 实测，boot 22:26）

**方法名（`com.android.phone` 进程，uid 1001）**：
```
hook OK com.android.phone.PhoneInterfaceManager#getAllCellInfo        -> java.util.List
hook OK com.android.phone.PhoneInterfaceManager#getCachedCellInfo    -> java.util.List
hook OK com.android.phone.PhoneInterfaceManager#getNeighboringCellInfo -> java.util.List
==== framework/telephony: 3 hook targets resolved, 1 missed ====
```
这些方法在本 ROM 返回**普通 `java.util.List`** ⇒ 原来的问题是**方法名**，其次是**类所在的进程**
（在 `com.android.phone` 而不是 `system_server`）。

**`system_server` 侧永远做不到**：`TelephonyRegistry` 根本没有 cell **取数**方法，dump 出来的 `cell*`
全是 notifier 且返回 void（`notifyCellInfo`、`notifyCellLocationForSubscriber`、`-$$Nest$mnotifyCellLocationForSubscriber`
等）⇒ `0 hook targets resolved` 不是匹配器的锅，是那里没有可 hook 的东西。

**真正卡住 `6-cells` 的结构性问题**：phone 进程里的 hook **装上了但不生效** ——
`EngineRuntime` 只在 `system_server`，phone 进程没有引擎，`activeSnap()` 恒为 `null`，
所以 `cellSimulation=true` 也不会出现 `cell.replaced`。**需要一个状态通道**（把模拟小区发布到
`Settings.Global`，像 `immoral_policy` 那样；或恢复一个最小 mirror 式 bus reader——被删掉的 client 层正好干这个）。

⇒ `6-cells` 应写成 **"named structural gap: phone 进程没有状态通道"**，而不是"原因不明"。



上述 `0/2 missed` 与"PLMN 正常（`mcc=46000`）但位置→小区不变"的原因，见 §6.1：`system_server` 里没有可 hook 的 cell getter，
phone 进程的 3 个 getter 已 hook 但因缺状态通道而不生效。

**10-independent：FAIL（投递过滤洞，优先级最高）**
```
delivery hook: LocationListenerRegistration#acceptLocationChange pkg=UNRESOLVED (independent)
delivery hook: … pkg=com.xiaomi.aicr / com.google.android.gms / com.xiaomi.metoknlp / com.xiaomi.location.fused
```
只白名单了 verifier，其他包照样收到投递；`pkg=UNRESOLVED` 就是漏的那一类身份。

**其它已确认的事实**
- `fw_status.json` 不可用：`E status file write DENIED (… EACCES)`，`system_server` 无法写 app 私有目录 ⇒
  oracle 只用 tick 日志；`18/25/15a` 之类稀疏采样行同理。
- 包名已改为 `io.github.twtwilight.immorallocation.debug`；`e2e/**` 通过 `Get-ModulePackageName` 动态解析，
  不需要改（这一轮改名后所有检查照常跑通）。
- `install.ps1` 修了两处：verifier 模块已不在 `settings.gradle`，构建目标改为按需；给 `activate_module.ps1`
  传参从 splat 改成具名（splat 会把 `android,com.android.phone` 顶到位置参数 `$ModulePackage` 上）。
- reboot 后 `adb` server 偶发失联，表现是"device did not come back"；`adb kill-server && adb start-server`
  即可，设备其实已经起来了。

## 7. 包体积 与 隐蔽性（size-stealth，2026-11-01）

用户四条投诉里的 **④ 包体积** 与**隐蔽性**两项都量了数：release APK **9.95 MiB → 7.79 MiB**
（`arm64` 单 ABI + R8 keep 收紧；`assets/cfg` 等 高德 资产另有 ~2.9 MB 可去，等渲染验证）。
隐蔽性清单见新文档 **`docs/STEALTH.md`**（16 个观测面 S1–S16）。

### 7.1 基线分解（`app-release.apk`，10 438 240 B = 9.95 MiB）

压缩后字节 / 未压缩字节，按占用排序：

| 条目 | 压缩 | 未压缩 | 说明 |
|---|---|---|---|
| `lib/arm64-v8a/*` | 2 936 146 | 6 711 408 | 高德 `libAMapSDK_MAP_v7_1_0.so` 2.77 MB + 我们的 `libimmoralhook.so` 0.16 MB |
| `classes.dex` | 2 197 011 | 4 666 068 | 5 271 个类：高德 1 164、compose.ui 1 020、foundation 588、我们的 341、osmdroid ~350 |
| `lib/armeabi-v7a/*` | 2 093 316 | 2 291 581 | **只有高德**（`libimmoralhook.so` 从来没有 v7a 构建） |
| `assets/cfg/**` | 1 620 353 | 4 218 284 | 高德 地图样式/室内包（`map.sty`/`map.sdkrs`/`idrres`） |
| `assets/map_assets/**` | 590 305 | 628 516 | 高德 图标/样式 data（名字是 `AMapEngineUtils` 的常量） |
| `assets/ae/res.zip` | 337 852 | 344 221 | 高德 AE 引擎资源（`AeUtil` 解包到 `data_v6/`） |
| `assets/offlinemapv4.png` | 217 597 | 217 597 | 以 **store**（不压缩）入包：`.png` 在 aapt2 的 no-compress 列表里 |
| `assets/map_custom/**` | 82 634 | 82 634 | 高德 自定义图层粒子 |
| `resources.arsc` | 33 156 | 33 156 | `localeFilters=zh,en` 已生效后的结果 |

### 7.2 逐步收益（每一步都有前后两个数）

| # | 改动 | APK 字节 | MiB | Δ | 约束 / 依据 |
|---|---|---|---|---|---|
| 0 | 基线 | 10 438 240 | 9.95 | — | |
| 1 | **ABI 策略：默认只出 `arm64-v8a`** | 8 344 420 | **7.96** | **−2 093 820** | `libimmoralhook.so` 只有 arm64 构建（`hook/src/main/jniLibs/arm64-v8a/`），32 位包**永远不可能注入**，v7a 的高德 .so 是纯死重。保留逃生口：`-PimmoralAbis=arm64-v8a,armeabi-v7a`、`-PimmoralAbiSplits=true`（出 per-ABI 包） |
| 2 | **R8：去掉 `-keep class org.osmdroid.** { *; }`**，改成只钉反射面 | 8 159 408 | **7.78** | **−185 012** | 旧的毯式 keep 把 osmdroid 全部 330 个类（1.09 MB class 文件）连同每个成员钉进 dex；本工程是直接实例化 osmdroid（`MapHost`/`TileSources`），R8 自己就能算出可达闭包。dex 2 197 011 → 2 089 368 |
| 3 | （同轮其它人的 UI 改动） | 8 164 366 | 7.79 | +5 KB | 记录当前树的状态，便于复核 |
| 4 | **去掉 `assets/cfg`（高德 样式/室内包）** | 6 542 016 | **6.24** | **−1 623 749** | 设备实测：地图与对照同屏一致（四臂表见 §7.3）；`ae/` 等其它包**不能**一起去 |

**device-free 验收（改完立刻跑）**：`23-module-integrity` **PASS**（`assets/xposed_init=com.immoral.location.hook.XposedEntry`、
mapping 里 hook 类 5 个、dex 里确实有 `com/immoral/location/hook/XposedEntry`）、
`24-apk-size` **PASS**。`24` 的预算在 `e2e/lib/checks_release.ps1:20`（`$MaxReleaseMb`，当时 15.0）；
Lead 会在本轮收尾时收到 **7.5**，以便卡住 6.24 MiB 的回归。

**评估过但没有收益的项**（task-7 要求逐项评估；留结论避免下一轮重复劳动）：

| 项 | 结论 |
|---|---|
| R8 full mode | **已经开着**（AGP 9.5.0 默认 full mode + `proguard-android-optimize.txt` + `isMinifyEnabled=true`）。要再压只能上 `-repackageclasses` / `-adaptclassstrings` 这类激进手段，估计收益 <100 KB，且对反射很重的 hook 代码有风险 —— 未采纳 |
| resource shrinking | **已经开着**（`isShrinkResources = true`）；`resources.arsc` 只剩 33 156 B，没有可挖的 |
| `localeFilters` | **已经开着**（`zh,en`），上一轮已量过 ~0.3 MB |
| native 符号剥离 | **无可收获**：`libimmoralhook.so` 是 xmake 预编译产物（445 344 B），AGP `stripReleaseDebugSymbols` 只去掉 8 B（本来就已剥离）；高德的 `.so` 是第三方产物，自带符号表也无权改 |
| `.png` 强制压缩 | **做不到**：`.png` 在 aapt2 的 no-compress 列表里，`assets/offlinemapv4.png` 以 217 597 B **store** 进包（同一文件 deflate 后仅 14 KB），AGP 没有"强制压缩"开关 —— 只能走 §7.3 的资产裁剪 |
| `-keep class org.osmdroid.** { *; }` 收紧 | **已做**（−185 012 B）；依据：本工程直接实例化 osmdroid，R8 自己算得出闭包 |
| per-ABI splits | **已实现为开关**（`-PimmoralAbiSplits=true`），默认仍出单个 universal APK —— CI 的产物路径固定是 `app-release.apk` |

### 7.3 「应用内地图就是高德」——一个被推翻的前提，与四臂 A/B 证据

**先纠正一个流传中的错误前提**：`e2e/lib/checks_ui.ps1:1316` 写着"产品跑在 osmdroid 栅格兜底上，
高德 key 是故意留的 stub"。**实测是错的**：`ReadyAMapSDK v1.0.4` + `3dmap 10.0.600` 用 AAR 里自带的 key
就能初始化并出图，设备日志里有 `I AmapHost: amap map loaded (tiles rendered)`，截图也是高德 样式
（南池子大街/东长安街/紫色商圈）。osmdroid 只是**初始化失败时**的兜底。

**四臂 A/B（每臂都是 uninstall → install → 冷启动 → 18–20 s → 截图 + 只取应用进程的 logcat）**：

| arm | APK 字节 | 截图 | `AmapHost: amap map loaded` | 结论 |
|---|---|---|---|---|
| 对照（`-PimmoralAmapAssets=true`，资产全留） | 8 165 765 | 1 021 801 B | **有** | 地图正常 |
| **只丢 `cfg`** | 6 542 016 | 1 020 639 B | **有** | **与对照同屏一致** |
| 只丢 `ae` | 7 827 484 | 130 276 B | 有（很晚） | **黑屏**：`java.io.FileNotFoundException: ae/res.zip` → `NullPointerException … GLMapState.setMapZoomer` |
| 六组全丢 | 5 274 854 | 130 197 B | 无 | **黑屏** |

⇒ 结论：**`assets/cfg`（1 623 749 B）可去；`assets/ae/res.zip` 是硬依赖，必须留**；
`map_assets/`（−602 041）、`map_custom/`（−84 047）、`offlinemapv4.png` + `amap_resource1_0_0.png`
（−242 742）**只**在黑屏的"六组全丢"臂里被一起拿掉过，**没有单独验证**，因此**不落地**；
每一个都需要自己的一次 install+截图才能算数（`-PimmoralAmapAssets=false` 就是这个实验开关）。

**落地的形态**（`app/build.gradle.kts`）：

```kotlin
val amapAssetPatterns = listOf("cfg")
val amapAssetsRequested = (project.findProperty("immoralAmapAssets") as String?)?.toBoolean()

androidComponents { onVariants { variant ->
    val keepAmapAssets = amapAssetsRequested ?: (variant.buildType != "release")   // release 丢，debug 留
    if (!keepAmapAssets) variant.androidResources?.ignoreAssetsPatterns?.addAll(amapAssetPatterns)
} }
```

* 机制：aapt2 的 `--ignore-assets`（`androidResources.ignoreAssetsPatterns`），**Variant API** 才能做到
  "release 丢 / debug 留"；模块级 `androidResources` 是 variant 无关的。
* **不要**用 `packaging.resources.excludes`：AGP 9 上它对 `assets/` 不生效（实测同样 glob，
  `assets/cfg` 15 个条目一个没少）。
* 逃生口：`-PimmoralAmapAssets=true` 把 `cfg` 放回去。

**证据边界（必须一起写）**：两组都跑在**同一个后端（高德）**、同一台设备/ROM、同一缩放、冷启动，
所以这次 A/B 证明的是"**出厂配置下**去掉 `cfg` 用户看不出差别"，**不**证明室内地图
（`cfg/idrres/ResPackIndoorMap.sdkrs` 在被丢掉的那份里）也没影响，也不证明其它 ROM、真实 key、
或后续 高德 SDK 版本上仍然等价。

**踩坑（值得记住，害了全组 20 分钟）**：Kotlin 的块注释是**可嵌套**的（和 Java 不同）。我在这个文件的
KDoc 里写了 `assets/cfg/**` 这类路径，里面的 `/*` 开了**嵌套注释**，收尾的 `*/` 只关掉内层，
于是**后面整个 `android { }` 块被当成注释吃掉**，AGP 报 "does not specify `compileSdk`" —— 而在编辑器里
`compileSdk = 37` 明明还在。**在任何 KDoc/块注释里不要写 `/**`**。

**已作废的选项（数字留档）**：曾经考虑过"去掉 `ReadyAMapSDK` 整体 → APK ~1.6 MiB"
（`libAMapSDK_MAP_v7_1_0.so` 2 774 166 B + 1 164 个 Java 类 ≈1.2 MB dex + 资产 2.89 MB ≈ 6.5 MB）。
既然高德 是本产品的**真实地图后端**，这条路**不再成立**：删掉 SDK 等于删掉地图。


### 7.4 隐蔽性（详见 `docs/STEALTH.md`）

* **已落地**：`hook/.../Log.kt` 分级 —— detail（安装横幅 + 调用期行，187 个日志点）默认只在
  `debug.immoral.log=1` 或 `.debug` 构建时输出；`e` 级永远输出（生产也要可诊断）。
  实测三次抓取共 1 315 行 `[ImmoralLocation]`，其中 `hook …` 782 行；1 315 行里**没有任何一行带坐标**
  （`lat=/lon=` 0 命中），wifi 身份已被 redact。
* **已落地**：`hook/.../Fakes.kt` 的 `immoral.*` extras 改为 `persist.immoral.extras=1` 才写；
  关时 `location.extras` 保持 `null`（不是空值），key 名/类型在开时与改动前逐字节一致。
  这条是**最高危面**：任何拿到 fix 的 App 用公开 API 就能读到模块名与各模拟开关。
* **交接**：`Settings.Global.immoral_policy` 全仓库**无读者**（只有写入点 `EngineRuntime.kt:457`）
  ⇒ 建议直接删（D1）；`immoral_cells` 是 phone 进程的唯一状态通道，保留（D2 建议照抄 STEALTH.md）。

### 7.5 复现

```powershell
# 尺寸（默认配置）
.\gradlew.bat --offline :app:assembleRelease
(Get-Item app\build\outputs\apk\release\app-release.apk).Length

# 资产策略（release 默认丢 assets/cfg；debug 默认留）
.\gradlew.bat --offline :app:assembleRelease                          # 6.24 MiB，cfg 不在包里
.\gradlew.bat --offline :app:assembleRelease -PimmoralAmapAssets=true # 7.78 MiB，四臂 A/B 的对照臂
# 验证 cfg 确实没被打进去：
Add-Type -AssemblyName System.IO.Compression.FileSystem
([System.IO.Compression.ZipFile]::OpenRead("$PWD\app\build\outputs\apk\release\app-release.apk")).Entries.FullName -like 'assets/cfg/*'

# 32 位逃生口 / per-ABI 包
.\gradlew.bat --offline :app:assembleRelease -PimmoralAbis=arm64-v8a,armeabi-v7a
.\gradlew.bat --offline :app:assembleRelease -PimmoralAbiSplits=true

# device-free 验收
. .\e2e\lib\common.ps1; . .\e2e\lib\report.ps1; . .\e2e\lib\checks_release.ps1
Invoke-ModuleIntegrityCheck; Invoke-ReleaseSizeCheck
```

## 8. 步频模拟 / GPS 信号模拟 在 framework-only 下的解剖（sensor-gnss，2026-11-01）

任务：`8-steps`（`counter delta=0 over 0 samples`）与 `9-gnss`（`no gnss line, samples=0`）。
两条都先回答"设计上该走哪条路"，再说"实际上缺了什么"，最后才是实测数字。

### 8.1 设计上应该走的路（intended path）

* **步频模拟**：`system_server` 里**没有** Java 事件投递点 —— `SensorService` 是原生的，app 侧的
  `SensorManager` 客户层已随 client 层删除。唯一的框架侧缝是 `libimmoralhook.so` 里的
  `SensorService::SensorEventConnection::sendEvents`（所有 app 的传感器事件都会经过它）。
* **GPS 信号模拟**：`LocationManagerService` 的注册点捕获 app 的
  `IGnssStatusListener`/`IGnssNmeaListener` transport，`GnssStatusProvider#onReportSvStatus`
  在上游把真实 SvStatus 就地改写，再加上每 tick 一次的合成推送；同时真值闸门
  （`blockRealGnssDelivery`）在模拟期间**取消真实** NMEA/SvStatus/measurements 的投递。

### 8.2 实际缺什么（三个具名缺口）

1. **`hook/.../SensorBoot.kt` 是一个空文件**（git `1107fb1` 起就只有 1 行空行）：步频模拟在框架层
   **从来没有实现过**，只有那个从未被验证过的原生改写。→ 本文件现在承担"缝的监视器"职责。
2. **改写无法凭空产生事件。** `TYPE_STEP_COUNTER` 是 on-change 传感器：手机平放在桌上（正是验收
   场景）时 HAL 一个事件都不产生，`sendEvents` 拿到的是别的传感器的批次 → 没有东西可改写。
   ⇒ 光有改写，`8-steps` 在任何静止状态下都只能是 `delta=0 over 0 samples`。
   框架侧唯一的出路是**伪造一个 `sensors_event_t`** 交给原函数 `sendEvents`（见 8.3）。
3. **引擎计数器被速度门控。** `SimulationEngine.accumulateSteps` 原来要求 `speed > 0.5 m/s`，
   而验收配置是 `mode=STATIC`（速度恒 0）⇒ 即使投递路径完好，计数器也永远是 0。
4. **GNSS 真值闸门把自己的合成推送也取消了。** `pushStatus`/`pushNmea` 是用**反射调用同一个
   proxy 方法**（`IGnssStatusListener$Stub$Proxy#onSvStatusChanged` 等）来投递的，而被 hook 的方法
   对反射调用同样生效 ⇒ 闸门把"真实的"和"我们的"一起取消，app 在 `gpsSignalSimulation=true`
   下也一行 GNSS 都收不到。**这正是 `9-gnss` 从 PASS 变 FAIL 的时间线**：
   `e2e/out/report-9.json`（12:27，`satellites=44`）与 `report-8-9-10.json`（00:00，`satellites=22`）
   都是 PASS，`e2e/out/report.json`（20:34）才是 `samples=0`；三次跑的是同一段 check 代码，
   `gpsSignalSimulation` 都是 `true`，差别只在闸门是否已在构建里。另外 `SatelliteSynth` 只产生
   8..14 颗，44/22 这种计数只可能来自"改写真实 SvStatus 后由框架管线投递"，即闸门之前的行为。
5. **注册点只在外层类里找。** `Refl.methodsWhere` 只沿父类往上走，而 Android 12+ 把
   `registerGnssStatusCallback` 放在 `LocationManagerService$LocalService`（内部类）⇒ 捕获永远 miss，
   推送没有听众。

### 8.3 本轮的改动（按缺口编号）

1. **`core/.../engine/SimulationEngine.kt`**：步数改为**节奏驱动** —— `running && stepSimulation`
   时按 `stepCadenceBpm/60*dt` 累加（整步进位、余数保留），与移动模式和速度无关；`stepDelta()`
   与 `tick()` 同源。`STEP_MIN_SPEED_MPS` 保留为"已不再参与判断"的命名常量。
   `StepSimulationTest` 13 例（含验收窗口 24×500 ms @120 bpm = 24 步）。契约更新见
   `docs/CONTRACTS.md` §3「Step counter semantics」。
2. **`hook/.../SensorBoot.kt`**（原为空文件）：框架侧"缝的监视器" —— 每 5 s 一行
   `steps seam: engine steps=… delta=… cadence=… native=[installed/calls/counter/detector] fabricate=[…]`，
   并从 system context 解析 `TYPE_STEP_COUNTER` 的 HAL handle（`Sensor#getHandle()` 不在 SDK stub 里，
   走反射）推给原生层；每 30 s 复核一次，handle 变了就重推、传感器消失就解除武装（stale handle 是
   唯一可能碰到**别的**传感器流的风险）。
3. **`native/src/sensor_hook.cpp`**：在改写之外新增**伪造** —— 批次里没有 counter 事件时，构造一个
   `sensors_event_t`（`version = sizeof`、`sensor = handle`、`timestamp = CLOCK_BOOTTIME`、
   `data = 引擎累计步数`），按 `60s/cadenceBpm` 节流（CAS，单调时钟），在真实批次之后交给**原函数**
   `sendEvents`；`SensorService` 自己的 per-connection 过滤按 handle 判定，未注册该传感器的 app 收不到，
   其它传感器流不受影响。所有路径都判空/判错，失败即"没有合成步数"，不抛异常、不分配。
   新增 JNI 导出写在同一个文件里（`nativeSetStepSensor` / `nativeStepSeam`），未改 `jni_bridge.cpp`。
4. **`hook/.../framework/GnssHooks.kt`**：闸门与推送共用一张纯策略表
   `core/.../engine/GnssTruthGate.kt`（`engineRunning / gateEnabled / independentMode / syntheticPush`
   → DELIVER|WITHHOLD），合成推送用 `withSyntheticDelivery { }` 打标（`finally` 清除），于是
   "真实数据照旧被扣、我们的合成数据放行"。捕获点扩到 `LocationManagerService` 的内层类
   （`LocalService`/`Gnss*`/`Gps*`）与厂商类，并额外 hook `IGnss*Listener$Stub$Proxy.<init>`
   —— 与注册 API 叫什么名字无关。新增证据行：`gnss capture: …`、`gnss seam: sats=… statusListeners=…
   syntheticPassed=… realWithheld=…`。

### 8.4 上手前的只读取证（boot 23:50:36，未安装/未重启）

在花掉设备窗口之前先只读取证，两个风险问题都已回答：

```
native bridge loaded: … loadLibrary: UnsatisfiedLinkError … couldn't find "libimmoralhook.so";
                      load(…/lib/arm64/libimmoralhook.so) ok
native hooks hooked=true version=immoralhook/1.0.0 (shadowhook) error=
```
⇒ `SensorService::SensorEventConnection::sendEvents` **在这台 ROM 上解析成功并已 hook**（`System.loadLibrary`
在 LSPosed 的模块加载器里必然失败，走的是 APK 里抽出的 `lib/arm64`；`NativeBridge` 的路径回退是对的）。

```
hook OK   LocationManagerService#registerGnssStatusCallback(IGnssStatusListener,String,String,String) -> void
hook OK   LocationManagerService#registerGnssNmeaCallback(IGnssNmeaListener,String,String,String) -> void
hook OK   IGnssStatusListener$Stub$Proxy.<init>.<init> (1)     hook OK   IGnssNmeaListener$Stub$Proxy.<init>.<init> (1)
hook OK   IGnssStatusListener$Stub$Proxy#onSvStatusChanged(GnssStatus) -> void  (+onGnssStarted/Stopped/onFirstFix)
hook OK   IGnssNmeaListener$Stub$Proxy#onNmeaReceived(long,String) -> void
==== framework/gnss: 18 hook targets resolved, 0 missed ====
gnss capture: 1 status hook(s), 1 nmea hook(s) over 2 holder(s)
```
⇒ 捕获在这台 ROM 上是**外层类**的方法；`$LocalService` 里只有一个 `() -> LocationTime` lambda
（`gnss registration inventory:` 那行就是为"万一在别处"准备的证据）。

步频相关的设备事实（同一次只读）：`step_counter` handle `0xbf`(非唤醒)/`0xc0`(唤醒)、
`step_detector` `0xb5`/`0xb6`，均为 `perm: ACTIVITY_RECOGNITION`；verifier 的该权限 `granted=true`
（所以"注册被拒"不是 `samples=0` 的解释）；`dumpsys sensorservice` 有 **9 条活跃连接**
（含 `Ambient Light Sensor Raw Data`、`WindowOrientationListener` 的 `device_orient`），
即 `SensorService` 一直在派发批次，`sendEvents` 会被**每条活跃连接**调用（per-connection 过滤在
`sendEvents` 内部完成），伪造事件不会被"完全没事件 ⇒ hook 从不被调用"饿死。
另外核对过设备上的 `.so` 就是本文件重建的那个（`…/lib/arm64/libimmoralhook.so` = 490 584 B，
`strings` 里有 `Java_…_nativeSetStepSensor`、`Java_…_nativeStepSeam`、`injected step event #%lld …`）。

### 8.5 复现

```powershell
# 原生层（改完 native/src/sensor_hook.cpp 必须重建，产物进 hook/src/main/jniLibs/arm64-v8a/）
# ShadowHook 现在是 xmake 包（`shadowhook-immoral 2.0.1`，定义在 native/packages/ 这个工程内包仓库里）：
# 第一次构建会联网拉源码包编译一次并缓存到 ~/.xmake/packages/s/shadowhook-immoral/，之后的构建只链
# libshadowhook.a。离线机器先跑一次 `xmake require shadowhook-immoral 2.0.1` 把包装进缓存即可。
cd native
xmake f -p android -a arm64-v8a --ndk="$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018" --ndk_sdkver=21 -m release
xmake
cd ..
Copy-Item native\out\arm64-v8a\libimmoralhook.so hook\src\main\jniLibs\arm64-v8a\libimmoralhook.so -Force
$nm="$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe"
& $nm -D --defined-only hook\src\main\jniLibs\arm64-v8a\libimmoralhook.so | Select-String 'native(SetStepSensor|StepSeam)'

# 纯逻辑
.\gradlew.bat --offline :core:test :app:assembleDebug

# 设备
.\e2e\install.ps1
.\e2e\run_matrix.ps1 -Only 8,9 -SkipUi
su -c "grep -hE 'native hooks hooked=|steps seam:|gnss capture:|gnss seam:|injected step event' /data/adb/lspd/log/verbose_*.log | tail -40"
```

### 8.6 本轮实测（boot 23:50，`-Only 9` → `-Only 8`，无安装/无重启）

**`9-gnss`：闸门修复被证明生效，剩下的 FAIL 是 harness 解析问题。**

```
[ImmoralLocation] gnss: real onSvStatusChanged withheld from a listener (gpsSignalSimulation=true)
[ImmoralLocation] gnss: pushing 13 satellites to 3 listeners
[ImmoralLocation] gnss: synthetic onSvStatusChanged passed the truth gate (1 so far, 3 real withheld)
[ImmoralLocation] gnss: synthetic onSvStatusChanged passed the truth gate (50 so far, 47 real withheld)
```
`syntheticPassed=50`、`realWithheld=47` —— 两个方向都对：我们的合成数据进得去，芯片真值照旧被扣。
app 确实收到了（9 条 `"source":"gnss_status_callback"` 样本，`maxCn0=43.869…`、`usedInFix=7`，即我们的合成 C/N0）。

但 check 仍读 `samples=0`，原因不是产品而是**行被截断**：这台 ROM 上 `Fakes.gnssStatus()` 造不出状态
（`fakes.gnss.drop dropping GnssStatus (round-trip probe failed: null)`），于是推的是被就地改写的**真实**
状态（`gnss: branch=mutate realCount=53 sats=13`，53 颗），verifier 的每状态一行 JSON 变成 **4 601 B**，
logcat 只留 **4 101 B** 并从中截断：

```
11-02 00:10:18.018 21335 21335 I ImmoralVerifier: VERIFY {"source":"gnss_status_callback","satellites":53,"usedInFix":7,"maxCn0":43.86936569213867,"sats":[{"svid":13,"cn0":43.869…
…{"svid":28,"cn0":0,"used":false,"constellation":6,"azimuth":0,"elevation":0},{
```
⇒ `ConvertFrom-Json` 失败，样本被丢弃。**两侧都已修**：(a) `e2e/lib/verifier.ps1` 改为按字段名从截断行里
抢救 `satellites`/`usedInFix`/`maxCn0`（Lead 落地）；(b) 本轮把推送用的状态改成经
`Parcel` + `GnssStatus.CREATOR` 构造的**我们自己的** 8..14 颗（§8.3），既让行变短，也顺手去掉真实星座尾巴。

**`8-steps`：引擎半边已修好并证明，伪造确实在跑，但事件没到 app —— 缺口已收窄并具名。**

```
[ImmoralLocation] steps seam: engine steps=0  delta=-1 cadence=120bpm sim=true running=false native=[installed=1 calls=308670 counter=0 detector=0 lookup_fail=0] fabricate=[handle=191 injected=0  skipped=0    enabled=0 steps=0  cadence=120]
[ImmoralLocation] steps seam: engine steps=9  delta=9  … native=[installed=1 calls=310060 counter=0 detector=0] fabricate=[handle=191 injected=8  skipped=1102 enabled=1 steps=9  cadence=120]
[ImmoralLocation] steps seam: engine steps=20 delta=11 … fabricate=[handle=191 injected=18 skipped=2442 enabled=1 steps=20 cadence=120]
[ImmoralLocation] steps seam: engine steps=30 delta=10 … fabricate=[handle=191 injected=27 skipped=3873 enabled=1 steps=30 cadence=120]
ImmoralNative: step seam: step-counter handle set to 191 (fabrication armed)
ImmoralNative: injected step event #1 value=1 handle=191 cadence=120
```
app 侧唯一的一条（**窗口之前**、来自 HAL 的真实事件，证明真实事件通路是通的）：
```
11-02 00:12:57.308 28296 28296 I ImmoralVerifier: VERIFY {"value":0,"elapsedMs":1362667,"kind":"stepCounter","ts":179354…
```

**结论（本轮 `8-steps` 的正式判定）**：
> 引擎侧已修好并实测：节奏驱动、`STATIC` 模式下 `steps` 0→9→20→30（120 bpm）。框架侧已证明**存在且在跑**：
> `SensorService::SensorEventConnection::sendEvents` 在 `system_server` 内 hook 成功（`hooked=true`，
> 16 s 内 `calls≈312 850`），`TYPE_STEP_COUNTER` handle 解析为 `191`，窗口内向 per-connection 原函数交付了
> 27 个伪造事件。**未证明**的是它们到达 app：窗口内没有一条 `VERIFY {"kind":"stepCounter"}`，而窗口之前
> 真实 HAL 事件是能到达 app 的（`value:0`）。⇒ 这不再是"framework-only 无法伪造"（伪造已跑起来），
> 剩下的是 `SensorService` per-connection handle 过滤处的**handle 身份**问题 —— 本机同时有
> `step_counter Non-wakeup 0xbf(191)` 与 `Wakeup 0xc0(192)`，只推 `getDefaultSensor` 的 191 时，
> 若 app 注册的是唤醒变体，事件会被过滤掉。下一轮：**一次带观测的运行**（推 `getSensorList` 的全部
> handle + 从第一条真实 step 事件学习权威 handle + 逐次注入日志），不是重新设计。

对应的两处改动已在代码里（见 §8.3）：`SensorBoot` 推 `getSensorList(TYPE_STEP_COUNTER)` 的**全部** handle；
`native` 侧保留候选集、从真实事件学习 handle（记录点在 `steps>0` 提前返回之前），并在 seam 行里输出
`handles=[…] observed=… injected=…` 以便下一次区分"被过滤"与"socket 被拒"。

### 8.7 第二次实测（boot 00:29:56，`install.ps1` 安装+重启，`-Only 9` → `-Only 8`）

**`8-steps` = PASS**（`counter delta=24 over 21 samples (monotonic=True, cadence ~24 @120bpm)`）。
两个 handle 的假设**成立**：app 注册的是**唤醒**计数器 `0xc0 = 192`，只推 `getDefaultSensor` 的
`0xbf = 191` 时被 `SensorEventConnection` 的 handle 过滤掉，所以上一次 `injected>0` 而 app 零样本。

```
steps seam: … native=[installed=1 calls=164944 counter=0 detector=0] fabricate=[handles=[191,192] observed=191 last=0   injected=0  skipped=0    enabled=0 steps=0  cadence=120]
steps seam: … native=[installed=1 calls=166304 counter=0 detector=0] fabricate=[handles=[191,192] observed=191 last=192 injected=16 skipped=1032 enabled=1 steps=9  cadence=120]
steps seam: … native=[installed=1 calls=167624 counter=0 detector=0] fabricate=[handles=[191,192] observed=191 last=192 injected=38 skipped=2341 enabled=1 steps=20 cadence=120]
ImmoralNative: step seam: 2 step-counter handle(s) from Java, fabrication armed
ImmoralNative: injected step event value=1 handles=2 last=192 cadence=120 total=2
```
app 侧（`ImmoralVerifier` 的 VERIFY 流，单调递增、120 bpm 约 2 步/秒）：
```
11-02 00:41:38.939 … {"value":19,…,"kind":"stepCounter","ts":…}   00:41:39.053 … {"value":20,…}
11-02 00:41:39.168 … {"value":22,…}   00:41:39.283 … {"value":23,…}   00:41:39.513 … {"value":24,…}
11-02 00:41:42.045 … {"value":25,…}   00:41:42.239 … {"value":28,…}
```
⇒ 全链路打通：节奏驱动引擎（0→9→20→30）→ `sendEvents` hook → 两个候选 handle 各投一个伪造事件 →
per-connection 过滤只放行 app 真正注册的 `192` → `onSensorChanged` → 12 s 涨 24 步。
`counter=0` 全程说明 HAL 一个事件都没产生（on-change 传感器 + 手机平放），**是伪造而不是改写**在交付；
`skipped=2341` 是 `60s/cadenceBpm` 节流。

**`9-gnss` = FAIL（作为 check），但产品侧被证明在交付。** 同一次运行里 app 既收到 ROM 自己的空状态（Start 之前），
也收到我们的合成状态：
```
11-02 00:40:03.220 … {"satellites":22,"usedInFix":0,"maxCn0":0,…        ← ROM 自己的（Start 之前）
11-02 00:40:09.381 … {"satellites":24,"usedInFix":0,"maxCn0":0,…}
11-02 00:40:13.832 … {"satellites":43,"usedInFix":0,"maxCn0":0,…}
11-02 00:40:17.708 … {"satellites":49,"usedInFix":7,"maxCn0":43.86936569213867,"sats":[{"svid":13,"cn0":43.869…   ← 我们的
11-02 00:40:19.239 … {"satellites":49,"usedInFix":7,"maxCn0":43.86936569213867,…}
11-02 00:40:22.673 … {"satellites":52,"usedInFix":7,"maxCn0":43.86936569213867,…}
```
闸门两个方向同时成立（与上一次 boot 相同量级）：
```
00:40:07.072 gnss: synthetic onSvStatusChanged passed the truth gate (1 so far, 3 real withheld)
00:40:22.281 gnss: synthetic onSvStatusChanged passed the truth gate (49 so far, 47 real withheld)
```
三条原因（都已定位，无需再猜）：
1. **行太长**：合成的状态是**被改写的真实对象**（44–52 颗），verifier 每状态一行 JSON 约 4.2–4.5 KB，
   logcat 只留 4 101 B（实测 `wc -c`）⇒ 行被从中间截断，`ts` 丢失 ⇒ 任何 `-AfterTs` 窗口都读不到它。
   本次改动的 `Parcel`+`GnssStatus.CREATOR` 构造方案在**这台 ROM 上不可用**：
   `once/gnss.parcel.none gnss: Parcel+CREATOR construction not usable on this build; falling back to the
   framework-built (mutated) status`（两个 PRN/flags 变体都没通过读回校验，且没有逐变体异常日志），
   于是安全回退到改写后的真实状态。
2. **窗口位置**：check 9 在 `Start` 后 2 s 取 mark、再等 5 s 读，早于 app 首次收到合成状态
   （活的 verifier 约 00:40:15 才注册，17.7 s 拿到即时推送）。
3. **选择策略**：旧 check 取"最后一条"，于是后到的、合法但空的 ROM 状态压过我们的。

**已由 Lead 落地的 harness 修复**：截断行改为从 `source` 推 `kind`；check 9 改为在窗口里取**最好**的样本
（`satellites` 最大、并列比 `maxCn0`），并在 per-satellite 数组被截掉时回退到标量 `maxCn0`。
**因此下一次 `-Only 9` 很可能在没有任何产品改动的情况下 PASS —— 那才是正确结果，因为产品一直在交付。**
记录口径：**`9-gnss` 的 FAIL 归因于 harness（行长度 + 窗口位置），不是产品缺陷**；`syntheticPassed` /
`realWithheld` 两个计数才是让 PASS 有意义的证据，check 本身不是。

**下一轮唯一的真实产品项**：我们推的是改写后的**真实**状态（44–52 颗），app 仍能看到真实星座的形状 ——
隐蔽性尾巴。做法：用 `Unsafe.putObject` 直接把 `mSvidWithFlags`/`mCn0DbHz`/`mElevationDegrees`/
`mAzimuthDegrees`/… 这些 final 平行数组换成 8..14 长度的新数组（正是 `Fakes` 对 `CellIdentity` 已经验证过的技巧），
状态变小、行不再被截断、尾巴消失。~30 行改动，仅限 `hook/.../framework/GnssHooks.kt`，需要一次设备窗口验证。

## 9. `6-cells`（基站模拟）根因与修复（cells-engineer，2026-11-01 23:5x）

**一句话**：合成算法没问题（位置 → 小区是纯函数，已有 7 条单元测试钉住），问题在**状态通道的写侧**：
`publishCells` 在引擎线程上解析 Context 时撞上**启动竞态**（`ActivityThread.currentActivityThread()` 还没就绪
⇒ `Method.invoke(null)` 抛 `NullPointerException`），而旧代码 `Log.once` 只报一次、
且**先把去重键提交再写**，于是「失败 → 静默 → 永不重试」，`Settings.Global.immoral_cells`
就留着上一次开机的那一行，`com.android.phone` 整场会话都在读它 ⇒ A 与 C 是同一个 `ci/tac`。

### 9.1 证据（读 LSPosed 全量日志，不是 logcat）

```text
/data/adb/lspd/log/modules_2026-11-01T23:00:54.728814.log
23:01:11.685 (system) EngineRuntime booted in 2253 (module dev)
23:01:11.686 (system) once/cells.write.err cannot publish the cells setting: NullPointerException

整段会话（23:00–23:26，305 行模块日志）统计：
  cells published = 0     cells.write = 1
  cell.replaced   = 3     （23:15:51 / 23:18:51 / 23:25:51，全部 "-> 4 synthetic cells"）

adb shell settings get global immoral_cells
  1|LTE,460,11,50274,129538244,436,4,-82,1;LTE,460,11,43001,…;…   ← 4 个小区
```
本次开机**一次成功写都没有**，而那一行里的 4 个小区正好等于 phone 进程对外服务的那 4 个 ⇒ 这行是
上一次开机的残留。`cell.replaced` 有、`cells published` 无，这一对就是「读侧正常、写侧全死」的指纹。

### 9.2 三个具体缺陷（都在写侧）

1. **Context 解析有启动竞态**：`ActivityThread.currentActivityThread()` 在引擎 `HandlerThread` 极早期为
   `null`，随后对**实例方法** `getSystemContext()` 做 `invoke(null)` ⇒ NPE；`context.contentResolver` 同理。
2. **去重键先提交后写**：`lastCellsEncoded = encoded` 写在 `putString` **之前**，一次失败就把该值标记成
   「已发布」，同值永不重试（首帧又是 `0|`，看起来完全合法）。
3. **`Log.once` 把失败藏起来**：唯一的错误行只出现一次，之后全是静默。

两个 stealth 项一起处理：`publishPolicy` 是**死代码**（写 `immoral_policy`，全仓无读者；
`settings get global immoral_policy` 实测 `null` ⇒ 它连一次都没成功跑过），且是免费指纹；
关掉模拟时写 `0|` 会永久留痕。

### 9.3 修复

- `hook/.../EngineRuntime.kt`
  - `systemContext()`：先在本线程试（`currentActivityThread()` → `getSystemContext()` → `currentApplication()`
    → `AppGlobals.getInitialApplication()`），失败就**一次性 post 到主线程**再试，成功即缓存；解析不到就
    **不提交去重键**，下一个 tick 继续重试（自愈）。
  - 失败**不再静默**：`logPublishStall()` 每 60 s 一行
    `cells channel: publish NOT landing (…); N failed attempt(s) so far, immoral_cells still holds the previous value`
    （N = 自上次成功写以来的累计次数），恢复时一行 `cells channel recovered after N failed publish attempt(s)`。
    这条线是「`6-cells` 是不是又坏了」的第一个判据。
  - 去重键只在**写成功后**提交；写完读回校验（`cells.verify.ok` / `cells.verify.mismatch` + 清键重试）。
  - 证据行带上位置与服务小区：`cells published to Settings.Global lat=…,lon=… serving=ci=…/tac=… count=…`。
  - 关模拟（或合成结果为空）时 `putString(…, null)` **删行**，不再写 `0|`。
  - 删除 `publishPolicy` / `immoral_policy` 写侧。
- `hook/.../framework/TelephonyHooks.kt::cellsFromSettings`
  - 主读改成**直连 provider 查询**（`Settings.Global.getUriFor("immoral_cells")`），绕过跨进程
    `NameValueCache`——那是「引擎在写、phone 永远读到旧值」的第二个候选通道（写侧修好后若仍冻结就是它）；
    `Settings.Global.getString` 只作兜底。500 ms 进程内缓存保留。
  - Context 解析同样带回退 + 缓存，缺行 = 模拟关闭（与旧 `0|` 等价）。
  - 证据行：`cells.read.direct` / `cells.read.absent` / `cells.context.miss`。

### 9.4 不变量与回归测试（`:core`，纯 JVM，`CellSynthTest` 7 例全绿）

- 500 m（正是矩阵的 A→C）必须换 serving cell；
- 5 km 必须换 TAC；
- **同一位置 byte-identical**（跨引擎实例、跨 tick 亦然）—— UI 预览与 hook 必须一致；
- 走回原点恢复同一组小区（位置 ↔ 小区是一一映射，不漂移）；
- 1 km 步行 20 步，每步都是合法的中国 LTE 小区。

### 9.5 复现 / 验证

```powershell
.\gradlew.bat --offline :core:test :app:assembleDebug
.\e2e\install.ps1
.\e2e\run_matrix.ps1 -Only 6

adb shell settings get global immoral_cells          # A 与 C 之间必须不同（关模拟后应为 "null"）
su -c "grep -hE 'cells published to Settings.Global|cells\.verify|cells\.publish\.stall|cells channel recovered|cell\.replaced|cells\.read' /data/adb/lspd/log/modules_*.log | tail -30"
```

判据：`cells published … serving=ci=…/tac=…` 随位置变化；出现 `cells.verify.mismatch` ⇒ 写没落地；
`cells published` 变了而 app 侧不变 ⇒ 看 `cells.read.direct`/`cells.read.absent`（读侧缓存）。

**方法论（踩过的坑）**：`logcat` 在这台机器上只有约 2 分钟缓冲（110k 行里模块日志只剩 57 行），
排查模块行为必须读 `/data/adb/lspd/log/modules_*.log`（整场开机、有历史）；`Log.once` 只适合
真正一次性的信息，**任何会重试的热路径都要用 `Log.rate`**。

### 9.6 设备实测：`6-cells` PASS（2026-11-02 00:28，boot 00:14，commit `45f94a6`+dirty）

```text
PASS  6-cells  A ci=107084627/tac=46625/pci=336 -> C ci=129538244/tac=50274; mcc=460 plmnOk=True
report: e2e/out/report-6-cells-lead.json（apkSha256 0F157384…EED250，frameworkLive=true）
```

**证据链（同一台设备、同一次开机）**：

1. 00:15:02 `=== installing framework layer in process=system_server pid=2389 ===` → `EngineRuntime booted in 2389`；
   `once/cells.context.direct … Context resolved on the engine thread`（修复后的 Context 解析生效）。
2. 同一毫秒、开机第一帧的唯一一次失败被**显式记下来**（旧代码这里就是静默点）：
   `cells channel: publish NOT landing (NullPointerException: Attempt to invoke interface method
   'android.app.IContentProviderHolder android.app.IActivityManager.getContentProvider(…)' on a null object reference);
   1 failed attempt(s) so far, immoral_cells still holds the previous value — retrying every tick`
   —— 即开机第 2 ms `ActivityManager.getService()` 还没就绪；**去重键没被提交 ⇒ 下一个 tick 重试成功**，
   之后整场再没有 stall 行（有 `recovered` 语义），这就是"自愈"和旧代码的差别。
3. `com.android.phone`（pid 5031，uid 1001）侧：`framework/telephony: 3 hook targets resolved`
   → `cells channel read via a direct provider query`（直连 provider 读生效）
   → `com.android.phone.PhoneInterfaceManager#getAllCellInfo -> N synthetic cells (List)`。
4. 验收行：A 与 C 的 serving cell 不同（`ci` 107084627 → 129538244、`tac` 46625 → 50274），
   手机号（PLMN 460/00）与 PCI 仍合法 ⇒ 位置 → 小区 → 发布 → phone 进程 → app 全链路通。

**判定口径**：`immoral_cells` 里那串就是引擎发布的原文；手机进程只做直连读 + 反序列化，
不再有跨进程 `NameValueCache` 这一层可以冻结它。若将来又出现"位置变、小区不变"，
先看 `cells channel: publish NOT landing (…); N failed attempt(s)` 这一行——它区分
"Context/AMS 没就绪（写侧）"和"写了但读侧冻结（读侧，看 `cells.read.direct`）"。

### 9.7 `6-cells` 回归时按这个顺序看（3 条日志 + 1 条地面真相）

先取全量日志（`logcat` 只有 ~2 分钟缓冲，**不要**用它下结论），再按下面四步走，每步都有明确的
"停在哪一侧"的结论：

```powershell
adb shell su -c "ls -t /data/adb/lspd/log/modules_*.log | head -1"          # 本次开机的完整日志
adb shell su -c "grep -E 'cells published to Settings.Global|cells channel|cell\.replaced|cells\.read|framework/telephony' <该文件>"
adb shell settings get global immoral_cells                                  # A 与 C 之间各取一次
```

**第 0 步（前置，5 秒）**：`=== installing framework layer in process=system_server` 与
`framework/telephony: install() reached (uid=1001)` + `==== framework/telephony: 3 hook targets resolved ====`。
缺 `uid=1001` 那条 ⇒ 问题在**作用域/LSPosed DB**（`activate_module.ps1` 的 scope 行），
和 cell 逻辑无关，别往下查。

**第 1 步（写侧是否跟随位置）**：找 `cells published to Settings.Global lat=…,lon=… serving=ci=…/tac=…`。
- 有两条 `lat` 不同、`serving` 也不同 ⇒ 写侧 OK，跳到第 3 步。
- `lat` 变了而 `serving` 不变 ⇒ **合成层**出问题：跑 `:core:test --tests '*CellSynthTest'`，
  它就是为这个不变量写的（500 m 换 cid / 5 km 换 TAC / 同点 byte-identical）。
- 整场一条都没有 ⇒ 写侧没成功，看第 2 步。

**第 2 步（写侧为什么没成功）**：`cells channel: publish NOT landing (…); N failed attempt(s) so far`。
- reason 是 `no Context on this thread yet` ⇒ Context 解析又退化了（`systemContext()` 的三个回退 + 主线程 hop）。
- reason 里是开机早期的 `IActivityManager.getContentProvider … null`、`… on a null object reference`
  且后面有 `cells channel recovered after N failed publish attempt(s)` ⇒ **正常开机抖动，自愈了**，不是 bug。
- 每 60 s 一直刷 ⇒ 真的卡住：此时 `settings get global immoral_cells` 一定还是**上一次开机**的旧值
  （这正是本轮那个 bug 的样子）；接着查 provider 是否拒绝写入（`Settings.Global.putString` 静默丢弃 / SELinux），
  以及 `cells.verify.mismatch` 有没有跟着出现（读回校验就是为了抓这个）。

**第 3 步（读侧是否真的替换了）**：`com.android.phone … PhoneInterfaceManager#getAllCellInfo -> N synthetic cells (List)`。
- 有这行、且 `N` 等于发布里的 `count=` ⇒ 全链路通；此时 app 仍看不到变化 ⇒ 看**采样侧**：
  `cellsFromSettings` 的 500 ms 进程内缓存、`run_matrix` 的 `-AfterTs` 时间窗、verifier 是否真的在自己轮询。
- 没有这行 ⇒ phone 进程要么没装 hook（回第 0 步），要么 `cellsFromSettings()` 返回了 `null`
  （`cells.read.absent` = 行被删/没写；`cells.context.miss` = 该进程取不到 Context；
  `cells.read.direct.err` = provider 查询失败，此时才轮到 `Settings.Global.getString` 兜底）。

**第 4 步（地面真相）**：`settings get global immoral_cells`。它不受任何进程缓存影响：
A 与 C 必须不同；关掉 cell 模拟后必须是 `null`（而不是 `0|`）。
这一条与第 1 步冲突（日志说在变、设置没变）⇒ 写没落地，回第 2 步；
与第 3 步冲突（设置变了、app 不变）⇒ 读侧，回第 3 步。




## 10. 本轮（framework-only 重构）总结与下一轮起点 2026-11-02 01:00

### 已达成（都有设备证据）
| 项目 | 结果 | 证据位置 |
|---|---|---|
| client 层删除 | 模块只在 `system_server` + `com.android.phone` 装 hook | `hook/README.md`、`XposedEntry.kt` |
| 位置注入 | 四个 provider 全部投递模拟点，`mock=false` | `docs/ACCEPTANCE.md` 快照 |
| 摇杆/速度 | 六档 1.40/3.30/5.50/13.90/230/7.50 m/s，ratio 1.00 | 同上（引擎 tick 判据） |
| 快速切换 | ~1.87 s | 同上 |
| **基站模拟** | **PASS** `A ci=107084627/tac=46625 -> C ci=129538244/tac=50274` | `e2e/out/report-6-cells-lead.json` |
| **WIFI 模拟** | **PASS** 5→6 个合成 AP，随位置变化 | `e2e/out/report-7.json` |
| **步频模拟** | **PASS** 120 bpm ⇒ 12 s 走 24 步；唤醒计数器 handle=192 | `e2e/out/report-8-steps-handles.json` |
| 包体积 | 9.95 → **6.24 MiB**（arm64-only + R8 + release 去掉高德 `assets/cfg`） | `docs/STEALTH.md` §4 |
| 隐蔽性 | `immoral.*` extras 默认不写（`persist.immoral.extras=1` 才写）；日志分级；删掉无读者的 `immoral_policy` | `docs/STEALTH.md` |
| 仓库卫生 | `e2e/**/*.ps1` 与 `docs/**/*.md` 现在纳入版本管理 | 提交 `45f94a6`、`1d9f869` |

### 下一轮第一件事（按价值排序）
1. **`9-gnss` 复测**：harness 已修（截断行按 `source` 推 `kind`、窗口内取最优样本），verifier 已限制每行最多 16 颗星。跑 `-Only 9` 应 PASS；同时把 `GnssHooks` 的"推送被改写的真实星座（44–52 颗）"改成 8–14 颗（`Unsafe.putObject` 裁剪平行数组），这是最后一条隐蔽性尾巴。
2. **`10-independent`**：非白名单应用仍能看到假位置（`pkg=UNRESOLVED (independent)` 投递）。真实 bug，优先级仅次于 1。
3. **`11-favorites`**：UI 侧复测（入口与列表已重做）。
4. **UI 评审**：`ui-polish` 的第二轮设计改动已提交，缺一轮截图证据（`dark` 与 `demo` 两套）。
5. 体积继续：`map_assets` / `map_custom` / 两个 png 各自单独验证（`-PimmoralAmapAssets=false` 是开关），每项最多再省 ~0.9 MB。

### 方法学沉淀（本轮踩过的坑）
- 设备是串行资源：任何安装/重启脚本**前台执行并逐步汇报**，不要后台跑再轮询（两次各损失 20 分钟）。
- `install.ps1` 的重启等待现在有 10 s 心跳 + 45 s 无信号自动重启 adb server。
- `Test-PackageInstalled` 曾因缺少 `(?m)` 把 `com.android.phone` 判成"未安装"。
- logcat 单行 ~4 KB：任何 `VERIFY` 行必须自带长度上限。
- Kotlin KDoc 里的 `/*` 会嵌套注释，吞掉整个文件（构建报"没写 compileSdk"就是这个）。

## 11. 原生层改用 xmake 包（ShadowHook 2.0.1） 2026-11-02

**改了什么**（提交 `4428650`，已推 `main`）

- `native/third_party/android-inline-hook/` 整棵删掉（80 个文件、21 330 行 C/CMake/Gradle 源码、
  851 418 B）。仓库里不再有第三方 C 源码。
- `native/xmake.lua` 不再 `add_files()` 那些源码，改成 `add_repositories("immoral-packages .")` +
  `add_requires("shadowhook-immoral 2.0.1")` + `add_packages("shadowhook-immoral")`。ABI 选择
  （`arch/arm64` vs `arch/arm`）、`xdl` 依赖、头文件安装都交给包。
- `native/packages/s/shadowhook-immoral/xmake.lua` 是**工程内包仓库**里的一个包：正文逐字来自
  xmake-repo 的 `packages/s/shadowhook/xmake.lua`，只多一处 `io.replace`（见下）。
- `.gitignore` 删掉 vendored 树的 4 条忽略规则 + 尾部 12 条"只保留 C 源码"的规则。

**为什么仍要留一行 patch（本轮唯一的技术判断）**

上游 `shadowhook_init()` → `sh_linker_init()` 会用 `xdl_dsym` **按名字**找 linker 的私有符号
（`soinfo::call_constructors` / `call_destructors` / `g_dl_mutex`）。这台 ROM（Android 15 / HyperOS）
上找不到，上游把这种情况当致命错误：

```
# 官方包（未打 patch）实测
native hooks hooked=false version=immoralhook/1.0.0 (shadowhook) error=shadowhook_init rc=12
# 12 = SHADOWHOOK_ERRNO_INIT_LINKER
```

我们只用 `shadowhook_hook_sym_addr()`（地址由自己 `dlsym` 得到），dlopen-monitor / "按符号名 hook"
用不到，所以让 init 打一条 warn 继续。打完就是：

```
native hooks hooked=true version=immoralhook/1.0.0 (shadowhook) error=
```

**产物对照**（同 NDK 27.2，arm64-v8a；`llvm-nm -D --defined-only` / `llvm-readelf -d`）

| 项 | 旧（vendored 源码编进目标） | 新（xmake 包） |
|---|---|---|
| 大小 | 493 872 B | 480 048 B |
| 导出符号 | `JNI_OnLoad` + 7×`Java_…NativeBridge_*` + `shadowhook_interceptor_glue` | 逐条一致 |
| `DT_NEEDED` | log, android, m, dl, c | 同上（无 `libshadowhook.so`） |
| 版本串 | `shadowhook version 2.0.1` | 同（+ `sh_linker_init failed, continue`） |

**设备证据**（重启后 `framework` 作用域重新加载，`e2e/out/report-8-native-package.json`）

```
PASS  8-steps  counter delta=31 over 1 samples (monotonic=True, cadence ~24 at 120 bpm)
```

**踩到的两个坑**

- 工程内包仓库里放一个与官方**同名**的 `shadowhook` 不生效：解析仍然命中 xmake-repo 的版本（实测
  装出来的 `.a` 里没有我们的 patch 串）。必须换包名，这就是 `shadowhook-immoral` 的由来。
- `add_repositories()` 的参数是**仓库根目录**，包要放在 `<根>/packages/s/<name>/xmake.lua`；写成
  `add_repositories("immoral-packages packages")` 会去找 `packages/packages/…`，报
  "not found in any repository"。这里的仓库根就是 `native/`。

**遗留（与本轮改动无关，没顺手改）**：`e2e/install.ps1` / `activate_module.ps1` 在重启后收集证据那一步
会抛 `Error formatting a string: … near offset 31`，脚本以 1 退出，但激活本身成功（数据库已核对、hook
日志已在）。定位在 `Reboot-Device` 之后的证据收集里，下次修。

## 12. 确定性验证：`8`/`10`/`11` 的顺序依赖已消除（qa-e2e，2026-11-02 03:0x）

目的不是"跑绿一次"，而是**证明同一构建上换顺序结果不变**（原先 `8-steps`/`10-independent` 单独跑 PASS、
跟着别的检查跑就 FAIL）。两个报告都在仓库里：

```
e2e/out/report-5 8 10 11.json   # 序列 A：先跑 5（原先会破坏 8/10 的顺序）
e2e/out/report-8 10 11.json     # 序列 B：同样三条单独跑，同一构建
```

### 12.1 序列 A：`.\e2e\run_matrix.ps1 -SkipUi -Only 5,8,10,11`
| check | 状态 | 数字 |
|---|---|---|
| `5-route` | **PASS** | advanced **479.87 m in 11.66 s**（gps）；暂停漂移 0 m；恢复 32.47 m |
| `8-steps` | **PASS** | `delta=33 over 4 sample(s) [oracle=steps-seam, verifier samples=2, seam window=4]`，monotonic=True |
| `10-independent` | **FAIL** | 白名单外 **fake=True**（samples=3，吸收缓存回放 gps@31.23042）；白名单内 reached=True |
| `11-favorites` | **PASS** | designed empty state（无收藏，空态提示存在） |

### 12.2 序列 B：`.\e2e\run_matrix.ps1 -SkipUi -Only 8,10,11`
| check | 状态 | 数字 |
|---|---|---|
| `8-steps` | **PASS** | `delta=33 over 4 sample(s) [oracle=steps-seam, verifier samples=2, seam window=4]` — **与 A 完全一致** |
| `10-independent` | **FAIL** | **与 A 完全一致**（fake=True / samples=3 / 同一条回放） |
| `11-favorites` | **PASS** | designed empty state |

### 12.3 三处 harness 修复（只在 `e2e/run_matrix.ps1`）

1. **`8-steps` 换主判据**：用 hook 自己的缝日志
   `steps seam: engine steps=64 delta=4 cadence=120bpm sim=true running=true`（每次伪造一行，不会被节流）。
   窗口前取**行数基线**，只解析窗口内新增的行；缝有 ≥2 点时以它为准，verifier 作次判据。
   报告里写清判据来源（`oracle=steps-seam`、`seamSamples`、`verifierSamples`）。两次运行
   verifier 只给了 1–2 个样本，缝给了 4 个、`delta=33` —— 这正是"数字不再取决于回调被节流了多少"。
2. **`10-independent` 吸收缓存回放**：新起的 verifier 会立刻收到框架缓存的 last location，
   **新行携带旧 fix**；在它之前取 mark，回放就被算成"白名单改动之后产生的样本"。
   现在按 provider 吸收到 **gps** 那条落地、再等 4 s 排空，才取 mark，只数严格更新的样本；
   吸收到的值写进 detail（`cached replay absorbed=gps@31.23042`）与 metrics。
3. **`10-independent` 禁止空样本假绿**：白名单外窗口若**一条样本都没有**，说明这次根本判不了 ——
   现在先重试一次（重启 streaming + 吸收回放 + 再测），仍然没有就报 **WARN "inconclusive"**，
   绝不记 PASS。Lead 观察到的"单独跑 PASS"实际上就是 `samples=0` 的空转；
   实测有样本时，非白名单 verifier 收到的是**假位置**（3 条 gps 样本），与 hook 侧
   `pkg=UNRESOLVED (independent)` 的投递证据一致 ⇒ `10-independent` 应按**真实缺陷 FAIL** 记录。

### 12.4 `11-favorites` 的自造前置（顺带修掉一个我自己引入的崩溃）
设备被 `pm clear` 或 UI 截图轮次清空后没有收藏，原先直接 FAIL。现在的行为：
计数为 0 或没有坐标行 ⇒ 点 `tapSave`/`actionFav` 造一条（`Get-UiElement -Name` 取的是 `$script:UiIds` 的键，
不是裸 tag —— 第一版写成 `-Id` 会在"空收藏"这条路径上抛异常），重开收藏面再读；
仍然为空则断言**设计空态**（`暂无|还没有|没有收藏|空空`），**没有空态提示的空列表依然 FAIL**，
并在 detail 里区分"点了但计数仍为 0"与"收藏动作不在屏幕上"。
另修：`$coordNodes[0]` 在空态分支上越界（`Index was outside the bounds of the array`），
现在只在**确实存在坐标行**时才跑"快速返回"那一段。

### 12.5 S1（收藏入口重复）与测试边界
设计侧已定：♡ 保留并继续带 `e2e.action.favorite`，`收藏 N` 变成纯导航链接。
所以自造前置的循环不用改。**注意**：`收藏` chip 与 ♡ 都能把计数从 0 变 1，
**任何自动化检查都无法分辨点的是哪一个** —— 这正是 R3 属于设计评审、而不属于断言的原因。


## 12. 事件记录：设备进入 LSPosed safe mode（2026-11-02 03:57）——不是本模块的崩溃

**现象**：system_server 反复崩溃，LSPosed 打印
`System server crashed too many times, stop all modules and enter safe mode`；
随后 `pm list packages` 返回空、`activate_module.ps1` 报 "ImmoralLocation is not installed"。

**真实原因（crash buffer 原文）**：
```
*** FATAL EXCEPTION IN SYSTEM PROCESS: main
java.lang.NullPointerException: pkg name: com.android.apex.cts.shim does not have a corresponding entry
  at KeySetManagerService.removeAppKeySetDataLPw
  at InstallPackageHelper.commitReconciledScanResultLocked
  at InstallPackageHelper.scanApexPackages
  at PackageManagerService.<init>
```
崩溃发生在 PackageManager 初始化扫描 APEX 时，`HookBridge`/`t.intercept` 帧只是**包裹层**，抛异常的是框架自身。
即：设备的 APEX/keyset 状态坏了（`com.android.apex.cts.shim`），与我们的 hook 无关。

**恢复步骤**：重新 `activate_module.ps1 -Packages android,com.android.phone -Prune`（会重写 DB 的 enabled 行，
等效于清掉 safe mode 标记）→ 一次干净重启 → 验证 `sys.boot_completed=1`、crash buffer 中
`FATAL EXCEPTION IN SYSTEM PROCESS` 计数为 **0**、`modules_*.log` 有本模块日志（153 行）。

**顺带修掉的 harness bug**：`e2e/lib/common.ps1` 的等待心跳字符串格式写错
（`({(0:N0}s elapsed)`），会让 `install.ps1` / `activate_module.ps1` 直接以
`Error formatting a string` 退出而不是等待；已修正。另：`Test-PackageInstalled` 在 PM 刚刚恢复、
`pm list packages` 返回不完整时会把已安装的包判成未安装，现已加 `pm path` 兜底。

## 13. `10-independent` 为什么在室内无法用"位置流"判定（qa-e2e，2026-11-02 05:3x）

产品的行为已经变了：独立模拟下模块**不再改写 provider 缓存**，按包过滤已被证明在工作
（`independent: dropped delivery to com.xiaomi.aicr/metoknlp/fused`、`substituted the last real gps fix for …`），
未解析身份的接收方只会拿到"看起来像真的"值。于是**位置流**这条路在室内是**结构性地不可判**：

1. 手机放在桌上没有新的平台定位；
2. `gpsSignalSimulation=false` 时 GNSS 真值闸门**故意扣留**真实卫星（这是设计，不是 bug）；
3. 独立模拟也不再往这条路径注入假值。

⇒ 白名单外窗口里 **0 条样本**既可能是"没有泄漏"，也可能是"没有东西可观察"。
**结论：`10-independent` 不能用流样本判定 PASS**——这正是 2026-11-02 两次运行得出相反结论的原因
（单独跑 `samples=0` 记 PASS，和别的检查一起跑则有样本并看到假值记 FAIL）。

### 13.1 现在的判据（不可空转）

同一条 check 里加**第二信号**：模块日志里的按包过滤证据，且在**切到白名单之前**采集，
避免白名单阶段的 `filter pass` 行污染：

```
independent: getLastLocation(gps) for a non-whitelisted caller -> real fix | no location
independent: dropped delivery to <pkg>
independent: substituted the last real gps fix for <pkg>
independent: provider last-known restored to the real fix on <provider>
```

- 任一出现 ⇒ `logEvidence=true`（这是"缓存保持真实 / 投递被拦"的正面证据，室内可测）；
- `logEvidence` 与 `outsideFake=false` 同时成立才算 PASS；
- **两者都没有**（无样本且无日志证据）⇒ 记 **WARN "inconclusive"**，永远不记 PASS。

指标里逐项落 `logEvidence` / `lastKnownGenuine` / `droppedForVerifier` / `substituted` / `cacheGenuine`，
detail 里直接写 `per-package evidence=[…]`，读报告的人不用再猜。

### 13.2 还缺什么（需要一次产品侧小改）

`getLastLocation(...) -> …` 这条最直接的证据**要有人真的调用 `getLastKnownLocation`** 才会出现。
当前 **verifier 没有这个探针**（`verifier/src/main/kotlin/.../VerifierActivity.kt` 只发流式 `VERIFY`）。
两条路：

1. 给 verifier 加一个 `--es lastknown true` 探针（约 15 行，`verifier/**` 属于 qa 的写域），
   调用 `getLastKnownLocation(gps/network)` 并打 `VERIFY {"kind":"lastKnown",…}`；
   **前提**是 `verifier` 模块要回到 `settings.gradle.kts`（构建文件，需 Lead 批准）。
2. 不改进程：靠日志里的 `dropped delivery to <pkg>` / `substituted …` 作为证据（今天已经可用）。

第 1 条能让断言变成"同一个 app 自己读到的值"（更接近用户可见行为）；第 2 条今天就能跑。

### 13.3 探针已落地并实测 —— 抓到一个**新的泄漏路径**（2026-11-02 06:0x）

`verifier` 增加 `--es lastknown true`：每秒读一次 `getLastKnownLocation("gps"/"network")`，
打 `VERIFY {"kind":"lastKnown","provider":…,"present":…,"lat":…,"lon":…}`（null 也显式发 `present:false`）。
`e2e` 侧把它当**主判据**：白名单外必须 ≥1000 m，白名单内必须 ≤300 m，读不到就 FAIL（不是 SKIP）。

两次连续运行（`e2e/out/report-10.json`）结果完全一致：

| 相位 | `getLastKnownLocation("gps")` 读到 | 判定 |
|---|---|---|
| 白名单外（`independentPackages=[]`） | `31.23042,121.4787`，d=**0 m**（= 模拟点 POINT_B） | **FAIL：非白名单应用通过 last-known 读到了模拟位置** |
| 白名单内（verifier 在名单里） | `31.23492,121.4737`，d=0 m（= POINT_C） | 正确 |
| 流（outside） | 跑 A `samples=0`；跑 B `fake=True, samples=3` | 因此需要缓存探针 |
| 模块日志 | `getLastLocation(...) -> real fix` / `dropped delivery to <pkg>` / `substituted the last real gps fix` / `provider last-known restored to the real fix` | 四条都在 |

⇒ **"日志说已按包过滤"与"app 真的读到了假值"同时成立**，唯一自洽解释：app 实际调用的重载
`LocationManager#getLastKnownLocation(String)`（以及 `(String,String)`）**没有被 hook**，
拿到的是 provider 缓存里上一次注入的值；日志里那条 `getLastLocation(...)` 来自**另一个**被 hook 的形态。

**给 hook 侧的待办**：把这两个重载纳入 `lastLocationHook`，并让独立模拟下的非白名单调用返回真实缓存或 null。

**方法学**：这条断言不可能空转 —— 白名单外读到假值 = FAIL，读到 null = FAIL（显式 `read returned null`），
只有"真实缓存且远离模拟点"才 PASS；室内可测，与 `gpsSignalSimulation` 无关。

**当天的构建状态**：`:app:compileDebugKotlin` 因 `SettingsScreen.kt` 的 `MaterialTheme` 冲突导入与
`Unresolved reference 'height'` 而失败（ui-polish 在改），因此本轮只单独安装 `verifier-debug.apk`
（`.\gradlew.bat :verifier:assembleDebug` 成功），**未重装 app、未重启、未重新激活**；
verifier **不在** LSPosed 作用域内（框架-only 的前提）。


