# 验收矩阵 / Acceptance matrix

本文件是本轮 UI/UX 重做的**验收证据表**：产品简报里的每个功能点 → 对应的自动化检查编号 → 状态 → 证据。

* 每一条状态都必须能追溯到 `e2e/out/report.json` 里的一条记录（`id` 字段）或 `docs/screenshots/` 里的一张截图。
* 状态只有四种：`PASS` / `FAIL` / `SKIP`（缺少前置条件，不是通过）/ `PENDING`（尚未在本轮设备上执行）。
* 不做"看起来差不多"的判断：任何 `PASS` 都必须来自脚本输出，人工只看截图做视觉评审（D 节）。

## 0. 如何复现

```powershell
# 1) 构建 + 安装 + 激活 LSPosed 模块（会重启设备，system 作用域必须重启才生效）
.\e2e\install.ps1

# 2) 全量验收矩阵（约 8~10 分钟，其中高德稳定性检查本身 3 分钟）
.\e2e\run_matrix.ps1

# 3) 只跑某几项
.\e2e\run_matrix.ps1 -Only 12,14
.\e2e\run_matrix.ps1 -Only 16 -AmapSeconds 60     # 快速回归：高德只跑 1 分钟

# 4) 只跑引擎层（不驱动 UI，最快）
.\e2e\run_matrix.ps1 -SkipUi

# 5) 截图（无 GUI，可被其他同事调用）
.\e2e\screenshot.ps1 map-peek

# 6) 静态检查 harness 自身（CI 也跑这个）
pwsh -NoProfile -File e2e\lint.ps1
```

结果写入 `e2e/out/report.json`（`-Only` 子集写 `e2e/out/report-<ids>.json`，不会覆盖全量报告）。

## 1. 四条投诉：一句话结论

读者是提投诉的人，所以先按投诉顺序回答，细节在后面各节。

| 投诉 | 一句话结论 | 证据 |
|---|---|---|
| **① UI/UX 太差、没有设计感、要 Material You** | **已重做，基本达成**：全屏地图 + pill 搜索条 + 右侧 FAB + M3 底部 sheet（peek/half/full）+ 长按落针卡片 + 单击 chip，动态取色与深色模式都在；7 张可评审截图通过，2 张因 harness/构建过期需重拍（不是产品缺陷，见 §8）。仍待改进：marker 体系偏薄（精度光环/收藏点/途经点徽标在截图里看不到）。 | [new-main-map.png](screenshots/new-main-map.png) · [new-tools-sheet.png](screenshots/new-tools-sheet.png) · [new-placecard.png](screenshots/new-placecard.png) · [new-tapchip.png](screenshots/new-tapchip.png) · [new-layers.png](screenshots/new-layers.png) · §7 逐条结论 |
| **② 摇杆方向错 / 功能没对齐**（摇杆、朝向、多级速度、基站、WIFI、步频、独立模拟、GPS 信号、更新频率、快速切换、收藏、路线、坐标搜索、Key-less 回退） | **方向语义已实测证明，UI 数字已拿到，引擎数字待完整作用域补测**：真实 UI 拖拽 → `SetJoystick(0,1)` → 13.9 m/s → 纬度 +0.000318°（0° 误差，上=北）；`14c` 56dp/152dp/208.4dp/48dp、`14b` 6×154px、`14` 35 对零相交、`15c` 途经点 0→1、`20` 5 个瓦片源、`21/22` 全通过。`0/12/12b/15a/15b/18/25` 因最后一轮 `system`-only 作用域而 **BLOCKED**（不是回归，见 §8）。 | §8 · §4 B 表 · `e2e/out/report-0 12 12b 14 15 18 25.json` |
| **③ 高德半分钟变回真实位置（隐蔽性）** | **现象已消失（framework-only 实测）；成因未隔离，且已证明与"本轮 hook 修复"无关**。当前构建：连续 180 s + 100 s 两次冷启动全部停在模拟位置（天安门），并且在"高德刚自行定位到真实杭州之后再开引擎"的起跑状态下 t+30/t+75 仍是天安门 ⇒ 不是缓存假象。**决定性对照**：用 `git worktree` 构建了**不含本轮任何 hook 改动**的 pre-fix 提交 `490ea3b`，同样协议下 **t+12/30/60/90/120 全部保持天安门** ⇒ ① 那个 t+12 s 回落今天用两个构建都复现不出来；② 它不是被"framework wifi 实例 hook"修好的（该 hook 已装上但高德从未调用：`wifi.withheld`/`wifi.replaced` 全空）；③ 最可疑的是当时中间态工作树里的 **client 层 fail-closed** 改动（`490ea3b` 里没有），但对它只是假设。产品决策：默认 **framework-only** 作用域，client 层只作兜底、默认不启用。 | [amap_t180.png](amap-evidence/verify/amap_t180.png) · [97_prefix_t120.png](amap-evidence/97_prefix/97_prefix_t120.png) · `docs/AMAP-STABILITY.md` §10/§11 |
| **④ 包体积** | **PASS：release 9.96 MB**（debug 47.26 MB，预算 15 MB）。复现：`.\gradlew.bat --offline :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` | `e2e/out/report-release.json`（`24-apk-size`） |
| （附）release 不得把 hook 剪掉 | **PASS**：`assets/xposed_init` 存在且指向 `com.immoral.location.hook.XposedEntry`；`mapping.txt` 保留 5 个 hook 类；release dex 中可搜到该类。以后任何 R8/proguard 改动破坏这一点，`23-module-integrity` 会红。 | `e2e/out/report-release.json`（`23-module-integrity`） |

## 2. 当前状态总览

| 项 | 值 |
|---|---|
| 本轮设备验收 | ⏳ **进行中**：检查点轮（截图流水线 + 前台/注入卫生）已完成，见 §8；数字矩阵（`0`、`12`…`22`）待最终轮 |
| 检查点轮结果 | 截图 7/9 可评审（`new-joystick.png` 无效、`new-route.png` 过期，均已定位原因并修了 harness，需重拍） |
| 自动化检查项 | `0` 配置卫生 + 引擎层 `1–11`（含 `11b`）+ UI/重做层 `12`、`12b`、`13`、`14`、`14b`、`14c`、`15a–15c`、`16`、`17`×2、`18`、`20`、`21`、`22` + 发布件 `23`、`24`（无需设备） |
| 上一轮基线（本轮重做前） | `pass=4 fail=6 skip=1`（2026‑11‑01 12:22–12:27） |
| CI | `.github/workflows/ci.yml`：`:core:test` + `:app:testDebugUnitTest` + `:app:assembleDebug` + `:verifier:assembleDebug` + `lintDebug` + e2e harness lint（无设备） |
| CI 当前状态 | ✅ **绿**（`:core:test` 228、`:app:testDebugUnitTest` 15、`assembleDebug`、`lintDebug`、harness lint 全通过） |
| 本地离线基线 | `.\gradlew.bat --offline :app:assembleDebug :core:test :app:testDebugUnitTest :app:assembleRelease` 全绿；release 9.96 MB |

上一轮基线里失败的 6 项（3 摇杆、4 多级速度、5 路线、8 步频、9 GNSS、10 独立模拟）是本轮要修的东西，
不是"已经通过"的东西；它们在本轮重新验收前一律保持 `PENDING`。

## 3. A 节 — 引擎功能（`e2e/run_matrix.ps1` 检查 1–11）

| # | 产品简报功能 | 检查项 id | 断言（可量化部分） | 上一轮 | 本轮 |
|---|---|---|---|---|---|
| A1 | 模拟生效（框架层注入） | `1-framework-spoof` | verifier 通过 `onLocationChanged` 收到目标坐标（±60 m），且 provider 列表含 gps/fused | PASS | PENDING |
| A2 | 位置快速切换 | `2-fast-switch` | `SetPosition` 后 <8 s 内到达新坐标（上一轮实测 ~1.1 s） | PASS | PENDING |
| A3 | 摇杆（速度×时间） | `3-joystick` | 位移/时间 与 `SpeedLevel` 标称值误差 ≤20%，方位与参考方向误差 <30° | FAIL（0 m/s） | PENDING |
| A4 | 多级速度 6 档 | `4-speed-levels` | WALK/RUN/BIKE/DRIVE/PLANE/CUSTOM 各自实测速度误差 ≤25% | FAIL | PENDING |
| A5 | 路线模拟（播放/暂停/继续） | `5-route` | 播放时位移 >5 m；暂停时 <3 m；继续后 >3 m | FAIL | PENDING |
| A6 | 基站模拟 | `6-cells` | 位置变化后 CI/TAC 变化，MCC=460 | PASS | PENDING |
| A7 | WIFI 模拟 | `7-wifi` | AP 列表非空且随位置变化 | PASS | PENDING |
| A8 | 步频模拟 | `8-steps` | `stepCounter` 单调递增且 Δ>0（12 s 窗口，120 bpm 参考 ~24 步） | FAIL | PENDING |
| A9 | GPS 信号模拟 | `9-gnss` | `satellites>0`、`usedInFix>0`、`maxCn0>0` | FAIL（usedInFix=0） | PENDING |
| A10 | 独立模拟 | `10-independent` | 白名单外应用看到真实坐标；白名单内看到模拟坐标 | FAIL | PENDING |
| A11 | 收藏 / 快速返回 | `11-favorites`（+`11b-favorite-return`） | 收藏列表渲染 + 点收藏项后位置回到收藏坐标（±300 m） | SKIP（旧 IA 找不到入口） | PENDING |

## 4. B 节 — UI/UX 重做验收（`e2e/lib/checks_ui.ps1`，检查 12–21）

对应 `docs/UIUX-REWORK.md` §0 的 7 条用户投诉。

| # | 投诉 / 规范点 | 检查项 id | 断言（可量化部分） | 本轮 |
|---|---|---|---|---|
| B1 | 摇杆方向是错的（投诉 #2） | `12-joystick-direction` | 4 个方向各拖 5 s：位移 ≥2 m，`up→纬度增大`、`right→经度增大`、`down→纬度减小`、`left→经度减小`，且实测方位与期望方位（0/90/180/270°）误差 ≤15° | **方向语义已实测成立**（手工，发布包 `44C38029…`）：真实 UI 拖拽向上 5 s → App 发 `SetJoystick(0,1)` → 引擎 `speed=13.9 m/s`、`moved=7.5 m` → 纬度 `31.230416→31.230734`（+0.000318°），经度不变 ⇒ **上=北、误差 0°**；摇杆盘 418px=`152dp`。自动化 `12` 行本轮 BLOCKED（见 §8a），待补 |
| B2 | 朝向（followHeading）语义 | `12b-follow-heading` | `heading=90°` 时摇杆"上"的位移方位 = 90°（±15°） | PENDING |
| B3 | 禁止 Toast（投诉 #3） | `13-no-toast` | `app/src/**` 中 `android.widget.Toast|Toast.makeText` 命中数 = 0，且驱动 UI 8 次交互期间 `dumpsys window` 无 toast 窗口 | 静态部分 **PASS**（0 命中，2026‑11‑01 无设备预检）；运行态 PENDING |
| B4 | 遮挡（投诉 #3） | `14-chrome-overlap` | 两种 chrome 状态下（摇杆关闭 / 摇杆开启）两两包围盒零相交：`searchbar / sheet / joystick / fab.tools / fab.layer / fab.joystick / fab.locate / snackbar`（该设备 440dpi，1080×2400 → 393×873dp） | PENDING |
| B5 | 出行方式 6 格不裁切（§1） | `14b-speed-row` | 6 个格子都存在（`e2e.speed.walk..custom`）、宽度极差 ≤1.25、无越界（不裁切） | PENDING |
| B5b | 设计令牌实测（§2/§4） | `14c-token-geometry` | 搜索胶囊高度 56dp ±8、摇杆底盘 152dp ±12、水平内边距 16dp；FAB 尺寸只报告不判定 | PENDING |
| B6 | 路线规划（投诉 #6） | `15a-route-ui` | 从 peek 行进入规划器；终点字段可输入坐标（`input text`，绕过输入法）；点「开始模拟」后位置移动 >5 m | **流程 PASS，位移 BLOCKED**：`destField=True`、`coordTyped=True`、`resultCard=True`、`source='数据来源 · 高德路径'`、`planningGone=True`、`startEnabled=True`；`movedAfterStart=0 m` 归因于当轮 `system`-only 作用域（注入未生效），不是规划器问题 |
| B6b | 多途经点（投诉 #6） | `15c-waypoints` | 规划器有「加途经点」按钮，点击后打开选点页，选中地点后 `e2e.route.waypoint.N` 字段数量增加，且步行/骑行/驾车 3 个方式 chip 存在 | **PASS**（0 → 1 个途经点，3 个方式 chip，393dp 无裁切） |
| B7 | 路线几何 / 播放沿 polyline | `15b-route-follow` | 直角折线（250 m 东 + 250 m 北，40 m/s）：全部采样点到折线距离 <60 m，进度单调不减且末进度 > 首进度+0.2 | **BLOCKED**（当轮注入未生效，`samples=0`）；`route_trace.ps1` 的 A/B/C 工件待完整作用域窗口补跑 |
| B8 | 高德稳定性（投诉 #4） | `16-amap-stability` | **PASS（视觉判定，见 C1）**：framework-only 作用域下高德 180 s + 100 s 两次冷启动全部停在模拟位置（北京），此前三个 build 均在 t+12 s 回杭州。判据仍是"证据完整性 + 人工/视觉判定"；旧的 dumpsys 断言降级为 *necessary, NOT sufficient*。 | PASS |
| B9 | 更新频率 | `18-update-interval` | `updateIntervalMs=1000` 与 `3000` 的投递间隔中位数比 ≥1.3，且 1000 ms 档 ≤2.5 s | PENDING |
| B10 | 地图 Key-less 回退（投诉 #5 / §1.7） | `20-keyless-map` | 主界面无「未配置 Key / 请配置 SDK」类文案（屏幕与源码都查），存在占屏 ≥80%×50% 的地图容器，且「图层」sheet 里至少 3 个瓦片源可选（`tile_preset_amap/tencent/tianditu/osm/custom`） | 源码部分 **PASS**（0 命中，2026‑11‑01 无设备预检）；屏幕/sheet PENDING |
| B11 | 长按落针卡片（§1） | `21-place-card` | 长按地图空白处 1.1 s 后出现落针卡片（`e2e.placecard` 或坐标文本） | PENDING |
| B12 | 单击点选 chip（Google-Maps 式） | `22-tapchip` | 单击地图中心后 4 s 内出现 `e2e.tapchip`，且 `撤销/收藏/加入路线` 三个动作齐全 | PENDING |
| B13 | 坐标搜索（简报） | `25-coordinate-search` | 点搜索条 → 输入 `39.908692,116.397532` → 内联建议卡 `e2e.search.suggestions` 出现且有一行结果；点该行后最近 fix 距目标 ≤1500 m | PENDING |

## 5. C 节 — 第三方应用（真实消费者）

| # | 应用 | 检查项 id | 断言 | 本轮 |
|---|---|---|---|---|
| C1 | 高德地图 `com.autonavi.minimap` | `16-amap-stability` | **PASS（修复后构建 `135A7C4C…`，framework-only 作用域）**。流程：`activate_module.ps1 -Packages android -Prune`（高德/百度/云运动 **均不在 scope**）→ 重启 → 引擎 STATIC / 全部模拟开关 off / 仿真点 天安门（真机在杭州）→ `amap_verify.ps1 -Seconds 180`。结果：`amap_t4 … amap_t180` **每一帧都是北京**（故宫/午门/南池子大街/王府井），第二次冷启动 100 s（`90_confirm_t20/t60/t100`）同样保持北京；**连"高德刚刚记住真实位置"的起跑状态也保持**——先关引擎让它自己定位到杭州（`94_real_learned.png`），再开引擎冷启动，t+30/t+75 仍是天安门（`94_after_t30/t75.png`）⇒ 不是缓存假象。**归因（已修正）**：`WifiServiceImpl`/`WifiScanningServiceImpl` 的实例 hook 确实装上了（`service 'wifi' registered at +11s via getService … hooked`），但**高德一次都没走这条路**——`wifi.withheld` / `wifi.replaced` 全空，连 `wifiSimulation=true` 的合成 AP 也没有被调用（`93_wifisim_t35.png`）。因此**不把 wifi seam 声称为修复原因**；最可能是 §5.1 的 **GNSS 真值投递闸门**（`gnss: real onNmeaReceived/onSvStatusChanged/onGnssMeasurementsReceived withheld` 每秒都在打），它恰好在 `244C8601…`（仍 t+12 回头）→ `135A7C4C…`（保持）之间落地。最终措辞见 `docs/AMAP-STABILITY.md` §10.2/§10.3，对照 build 未做前保持"最可能"。 | PASS |
| C2 | 百度地图 `com.baidu.BaiduMap` | `17-third-party-baidu` | **部分**：framework-only 作用域下 hook-loc 的 `80_baidu.png` 显示百度地图**渲染的是注入位置**（天安门 / 南池子大街），但**未做** 17 秒长时 smoke（无 `FATAL EXCEPTION` 检查、无连续位置采样）。 | 部分 |
| C3 | 云运动 `com.yunzhi.tiyu` | `17-third-party-yunzhi` | **部分**：`81_yunzhi.png` 显示云运动能**正常启动**，未做位置正确性与长时稳定性验证。 | 部分 |

## 6. D 节 — 人工视觉评审（截图集）

自动化只能证明"不重叠 / 有节点"，视觉质量必须人看。截图全部由 `e2e/screenshot.ps1` 生成，落在
`docs/screenshots/`。

| 场景 | 文件 | 评审点 | 本轮 |
|---|---|---|---|
| 地图 + peek 卡片 | `docs/screenshots/new-main-map.png` | 全屏地图、pill 搜索条、右侧 FAB 竖排、peek 行状态 + 6 格出行方式 | ✅ 已评审 |
| 摇杆（按下态） | `docs/screenshots/new-joystick.png` | 152dp 圆盘、拇指偏移、**「上 = 北」**、路线起/①/终徽标 | ✅ 已评审 |
| 工具面板（half） | `docs/screenshots/new-tools-sheet.png` | 基站/WIFI/步数/GNSS/更新频率/独立模拟 | ✅ 已重拍 |
| 路线规划器（full） | `docs/screenshots/new-route.png` | 起点/途经点/终点、出行方式、主操作**不再裁切** | ✅ 已重拍评审 |
| 长按落针卡片 | `docs/screenshots/new-placecard.png` | 地点名/坐标/距离 + 主次操作 | ✅ 已评审 |
| 单击 chip | `docs/screenshots/new-tapchip.png` | 撤销/收藏/加入路线 三动作 | ✅ 已评审 |
| 搜索（坐标查询） | `docs/screenshots/new-search.png` | 内联建议卡 + 键盘 | ✅ 已评审 |
| 图层 sheet | `docs/screenshots/new-layers.png` | 5 个瓦片源、天地图禁用并提示需 Key | ✅ 已评审 |
| 深色模式 | `docs/screenshots/new-dark-mode.png` | 动态取色深色主题（最末一次重拍在本构建上） | ✅ 已评审 |
| 收藏 marker | `docs/screenshots/new-favorite-marker.png` | 收藏点 marker 与标签 chip | ✅ 已评审 |
| 高德前台 | `docs/amap-evidence/verify/amap_t*.png` | 逐帧城市（北京→杭州 = FAIL 证据） | ✅ 已评审（FAIL） |
| 百度地图 / 云运动 | `docs/screenshots/acceptance-baidu.png` / `acceptance-yunzhi.png` | 由 hook-loc 的 smoke 截图补齐 | 待 hook-loc |

## 7. E 节 — 「看起来像不像 Material You」逐条评审

投诉 #1 是"UI/UX 太差、没有设计感、要 Material You"，所以这一节按 `docs/UIUX-REWORK.md` §1–§4
逐条列判据。**负面发现同样有价值**：这一节不是"证明我们做对了"，而是"把仍然不对的地方写清楚"。

评审方式：先看自动化能测的那几条（14/14b/14c 已经测了尺寸与遮挡），其余由 reviewers 对着
`docs/screenshots/` 里的截图逐条勾。`评审结果` 列只允许 `OK` / `偏差（说明）` / `缺失`。

| # | 规范条款 | 判据（能看出来 / 能量出来） | 评审结果 | 证据 |
|---|---|---|---|---|
| E1 | §1 顶栏 | 搜索条是 pill（56dp 高、圆角全圆），右侧独立工具头像/按钮，两者 8dp 间距；搜索条距屏幕左右各 16dp | **OK**（视觉）；dp 数值待 `14c` | `new-main-map.png` + `14c-token-geometry` |
| E2 | §1 地图 | 地图**全屏、无边距**铺满，无任何常驻色块横幅；顶部/底部 chrome 浮在图上而不是挤占地图 | **OK** | `new-main-map.png` |
| E3 | §1 右侧 FAB | 竖排 FAB（图层 / 摇杆 / 定位）位于 bottom-end、在 sheet **之上**，互不重叠，且 sheet 全展开时淡出 | **OK**（peek 可见；half 淡出符合规范） | `new-main-map.png` / `new-tools-sheet.png` |
| E4 | §1 底部 sheet | 三档停泊（peek / half / full）可拖动；peek 行 = 状态点 + 地点 + 出行方式与速度；顶部 28dp 圆角 + 拖拽把手 | **OK**（peek/half 已见；full 见 `new-route.png`） | `new-main-map.png` / `new-tools-sheet.png` |
| E5 | §1 出行方式行 | 6 个等宽格子（步行/跑步/骑车/驾车/飞机/自定义），**标签不被裁切** | **OK**（图标式、等宽、无裁切）；极差待 `14b` | `14b-speed-row` + `new-main-map.png` |
| E6 | §1 主操作行 | 「暂停 / 路线 / ♡」三个主操作可见、可点、不与 FAB 重叠 | **OK** | `new-main-map.png` |
| E7 | §1 half sheet | 工具面板含 基站 / WIFI / 步数 / GNSS / 更新频率 / 独立模拟 / 收藏，全部可达 | **OK（5 个模拟开关 + 更新频率已见，收藏区在其下方未截到）** | `new-tools-sheet.png` |
| E8 | §1 full sheet | 路线规划器：起点 / 终点 / 途经点、出行方式 segmented、结果卡、开始模拟/保存/清除 | **OK（已重拍验证）** — 重拍后：「规划路线」+「保存路线」完整标签 + 红色图标删除，**裁切问题已消失**；起点 A / 途经点 1（带上移下移+删除）/ 终点 B 齐全；peek 收成单行标题；「路径规划」标题有 16dp 左边距。截图里曾出现的途经点坐标被裁成 `31.23040, 1…`，route-planner 在 16:57 构建上把坐标标签规范成 4 位小数短式（`31.2304, 121.4737`）后已消除；该构建还把结果卡与 `e2e.route.start` 一起钉进底部固定区（不再依赖滚动位置） | `new-route.png`（修复后）· §8 `new-route.png`（修复前，裁切证据） |
| E9 | §2 表面层级 | chrome 一律 `surfaceContainerLow` @0.94 + 描边；FAB `surfaceContainerHigh`；激活态 `primary/onPrimary` | **OK**（无生硬纯白块，选中态为 primary） | `new-main-map.png` |
| E10 | §2 动效 | sheet 弹簧 / 摇杆 150ms scale-in / 无花哨动画 | **未评审**（静态截图判断不了） | 截图集 |
| E11 | §2 图标 | 只用 material icons，无 emoji | **OK** | 全部截图 |
| E12 | §2 字体 | 坐标为 `labelSmall`+`tnum`，不当主标题 | **OK**（搜索条下方小号 `31.2304, 121.4737`） | `new-main-map.png` / `new-placecard.png` |
| E13 | §3 marker 体系 | 位置点 + 外环 + 精度光环；bearing 扇形；收藏点；途经点序号徽标；起终点异色 | **基本 OK**（`new-joystick.png` 同时可见：位置点+外环、路线上的 **起 / ① / 终** 徽标；收藏点在 `new-favorite-marker.png`）。精度光环在繁忙栅格图上偏弱，ui-maps 已在 16:52 构建上给光环加了 1.5dp `primary@30%` 细边（填充仍 12%），该行如需转"OK"需一张新构建的 `new-main-map.png` | `new-main-map.png` / `new-joystick.png` / `new-favorite-marker.png` |
| E14 | §3 路线绘制 | 12dp surface + 7dp primary 双描边，能看出道路级折线 | **OK**（`new-joystick.png` 里的折线有明显外描边与圆头，且带起终点徽标） | `new-joystick.png` |
| E15 | §4 摇杆 | 152dp 圆盘、78% 表面 + 8dp elevation、44dp 拇指；**默认关闭** | **OK** — 实测 **152dp**（418px @440dpi），盘面半透明+描边，拇指单独着色；默认关闭已确认 | `new-joystick.png` + `14c-token-geometry` |
| E16 | §4 方向语义 | 盘面标"上=北"；跟随时有 `-heading` 刻度环 + N 标记 | **OK** — 盘面正下方印着 **「上 = 北」**；跟随模式的刻度环需另拍（未验证） | `new-joystick.png` |
| E17 | §5 Snackbar | 顶部（搜索条下 8dp）、`inverseSurface`、16dp 圆角、与 sheet/FAB/摇杆零重叠；无 Toast | **部分**：代码符合（TopCenter + min 48dp）；运行态待 `14`/`13` | `14-chrome-overlap` + `13-no-toast` |
| E18 | §7 无横幅 | 粉色「SDK 未配置 Key」横幅消失，图层选择在「图层」sheet | **OK**：`new-layers.png` 无横幅，5 个瓦片源可选；源码 grep 0 命中 | `new-layers.png` + `20-keyless-map` |
| E19 | 深色模式 | 深色下动态取色、对比度足够 | **待细看**：`new-dark-mode.png` 已拍到，最终轮复核对比度 | `new-dark-mode.png` |

## 8. 检查点实测（2026‑11‑01 14:45–15:50，**非最终轮**）

设备 `23049RAD8C` / Android 15 / 1080×2400 @440dpi。APK `sha256 F4BCFD7E…D21A1AFF`（14:38 构建）。
安装方式 `e2e/install.ps1`（install + `activate_module.ps1` + reboot）；framework 已确认生效——
`dumpsys location` 的 fix 带 `immoral.*` bundle，这是"注入确实来自我们的 hook"的直接证据。

本轮只跑**截图流水线**，数字矩阵留到最终轮（route-planner 的 polyline trace 与 hook-loc 的高德证据也要排在它前面）。
截图结论已并入 §7 的 E 表；下面只记"这批图里哪几张不能用于评审、为什么"：

| 文件 | 可用性 | 说明 |
|---|---|---|
| `new-main-map.png` / `new-tools-sheet.png` / `new-placecard.png` / `new-tapchip.png` / `new-layers.png` / `new-search.png` / `new-dark-mode.png` | ✅ 可评审 | 见 E 表 |
| `new-joystick.png` | ❌ **无效** | 画面里没有摇杆盘：`Get-JoystickBounds` 的几何兜底把**摇杆 FAB**（~150px）当成了盘（真实盘 152dp≈418px），"按住"的是 FAB。已在 `e2e/ui_gesture.ps1` 修（≥300px 且排除 `e2e.fab.*`），该图必须重拍 |
| `new-route.png` | ⚠️ **过期** | 拍的是 14:48 之前的构建：底部三按钮把「规划路线」裁成「**规划路**」——正是 route-planner 随后修掉的那个问题。这张现在是"修复前"的证据，不是当前状态的证据 |

**本轮顺带修掉的 harness 缺陷**（全部在 `e2e/**`，未触碰产品代码）：
1. `-Only 12,13` / `-Only new-a,new-b` 抛 `Cannot convert value to type System.String` → 两个脚本的 `-Only` 改为 `[string[]]`（文档里的示例此前从未真正可用）。
2. 摇杆盘几何兜底匹配到 FAB → 见上表。
3. 连续两次 BACK 把 App 退到桌面，导致三张截图拍成了小米启动器 → 新增 `Ensure-Foreground` / `Close-TopSurface`，每个状态前重新确认前台，`Save-Missing` 现在连屏幕树一起落盘（`e2e/out/missing-<state>.txt`）。
4. "预期为空"的探测污染选择器审计 → `Get-UiElement -Optional` 区分"预期缺失（如摇杆默认关闭）"与"真的找不到"。
5. 已核对 APK 与源码树一致：重建后 SHA256 未变才安装；`report.json` 落 `apkSha256` / `gitHead` / `gitDirtyFiles` / `topActivity`。

## 8a. 检查点数字（来自 15:25–15:50 的两次矩阵运行）

### 通过项（有实测数字）

运行环境：设备 `23049RAD8C` / Android 15 / 1080×2400 @**440dpi**；**APK `244c8601…`（本地构建与已安装 APK 哈希一致，免重装）**。

**截图与构建的绑定关系**（避免"图是旧构建、数字是新构建"的歧义）：9 张 `new-*.png` 中有 3 张（`new-joystick`、`new-route`、`new-tools-sheet`）在 **16:45–16:47 于 `244c8601…`** 上重拍，其余 7 张摄于 15:30–15:31（`44C38029…`）。两者 UI 完全一致：`app/src` 最后一次修改是 **15:02:00**（`Panels.kt`），两次构建之间只有 `hook/**` 变化（`WifiHooks.kt` 16:40、`GnssHooks.kt` 16:14），所以早期截图对 UI 评审同样有效。

| 检查 | 结果 |
|---|---|
| `14c-token-geometry` | **PASS** — 搜索胶囊 **56 dp**（左侧 16 dp 内边距）、摇杆盘 **152 dp**、peek detent **208.4 dp**、四个 FAB 均 **48 dp** |
| `14b-speed-row` | **PASS** — 6/6 格，宽度 `154,154,154,154,154,154` px（=56 dp），极差 **1.00**，无越界 |
| `14-chrome-overlap` | **PASS** — 摇杆关/开两种状态共 **35 对**元素，零相交 |
| `15c-waypoints` | **PASS** — 加途经点 → 选择器 → 选中地点后 `e2e.route.waypoint.0` 出现（0→1），3 个出行方式 chip 齐全 |
| `13-no-toast` | **PASS** — 源码 0 命中；8 次 UI 交互期间 `dumpsys window` 无 toast 窗口 |
| `20-keyless-map` | **PASS** — 屏幕/源码均无 Key 横幅；「图层」sheet **5 个**瓦片源可选 |
| `21-place-card` | **PASS** — 长按 → 卡片 `44,1418-1036,1750`，`31.2321, 121.4737 · 距当前位置 191 m` |
| `22-tapchip` | **PASS** — chip `44,1612-777,1739`，撤销/收藏/加入路线 齐全 |
| `harness-selectors` | **PASS** — 除 `e2e.route.notice`（仅"直线回退"时才存在）外所有 `e2e.*` 都能解析 |
| `23-module-integrity` / `24-apk-size` | **PASS** — release 保留 `assets/xposed_init`→`XposedEntry` + hook 类；**9.96 MB**（debug 47.26 MB） |

### 手工实测：方向语义（投诉②的核心答案）

在完整作用域安装（15:44，`44C38029…`）上，用真实 UI 手势驱动：
`input swipe` 从摇杆盘中心向上 5 s ⇒ App 发出 `SetJoystick(x=0, y=1)` ⇒ 引擎 tick `speed=13.9 m/s moved=7.5 m` ⇒
`dumpsys location` 最后定位 `31.230416` → **`31.230734`**（纬度增大、经度不变）。
**结论：上=北、右=东成立，误差 0°。** 摇杆盘实测 **418×418 px = 152 dp**。
截图 `new-joystick.png` 里盘面下方直接印着 **「上 = 北」**，与测量一致。

### 最后一轮窗口（17:04–17:27，`9e0c5551…` = 16:57:57 构建，作用域 `system`+App+verifier）

| 检查 | 结果 |
|---|---|
| `12-joystick-direction` | **部分实测，且已测的那条是对的**：`left` → **4.4 m，方位 277.3°（期望 270°，误差 7.3°）**，15 个采样，`Δlat +0.0000050 / Δlon −0.0000450`（向西，方向正确）。`up`/`right`/`down` 同轮读 0 m。 |
| `15c-waypoints` | **PASS（复现）** — 途经点 1 → 2，选择器打开，3 个方式 chip |
| `15a-route-ui` | 流程 PASS（`destField` / `coordTyped` / `resultCard` / `source=数据来源 · 高德路径` / `planningGone` / `startEnabled`），`movedAfterStart=0 m` |
| `15b-route-follow` | **harness 错误**（广播接收器中途失效 → 回退 prefs → 无 prefs 文件而中止），非产品结论 |

**为什么 3 条 0 m 不当作产品缺陷**：四次拖拽构造完全相同（同盘、同 157 px 偏移、同 5 s `input swipe`），却只有一次到达；若 pad→bridge→engine 链路断了，`left` 不可能给出正确的 277.3° 向量。因此归因为**合成手势投递不稳定**（地图因上一次 `SetPosition` 重新居中时按下指针，App 把 swiping 当点击/滚动），属 harness 问题；修法明确：按下前等地图稳定，测量前先确认日志出现 `SetJoystick`。

**同时发现的 harness 缺陷（重要）**：`dumpsys location | grep immoral.` **不能**当注入探针——本轮门禁报"无 bundle"的同时，几秒后真实拖拽让注入坐标移动了 4.4 m（只有引擎在工作才可能）。该 bundle 只在"刚向监听者投递过 fix"的短窗口内存在。正确探针是 **verifier 流**（`VERIFY {"kind":"location",…}` 证明值经 `requestLocationUpdates` 下发）。`0-config-hygiene` 因此可能给出**假 FAIL**。

### 其它 BLOCKED 行（作用域/时序所致，既不记 PASS 也不记 FAIL）

`0-config-hygiene`、`12b`、`18`、`25` 在最后一轮同样读 0；结合上面的发现，它们受"卫生门探针不可靠 + 合成手势投递不稳定"两个 harness 缺陷影响，需在修好探针（改用 verifier 判定）后重跑。上一轮（`system`-only 作用域）的读数是同一现象。

## 9. 已知问题 / 待修

| 问题 | 归属 | 状态 |
|---|---|---|
| 上一轮 3/4/5/8/9/10 六项失败（摇杆 0 m/s、速度档位全偏低、路线恢复不动、步数不动、GNSS `usedInFix=0`、独立模拟白名单失效） | `hook-loc` / Lead | 本轮方向语义已实测修复；其余待完整作用域下复测 |
| 高德回落到真实位置（投诉 ③） | `hook-loc`（`docs/AMAP-STABILITY.md`） | ✅ **行为已修复（framework-only，实测）**：三次独立冷启动（180 s / 100 s / 65 s，含故意拉起第二个注入器）＋「刚记住真实杭州后开引擎」也保持模拟位置。**归因修正**：wifi seam 已装上但高德从未调用（`wifi.withheld`/`wifi.replaced` 全空），最可能的实际原因是 **GNSS 真值投递闸门**（§5.1）；对照 build 待做，未做前保持「最可能」 |
| 收藏入口在旧 IA 中不可达（上一轮 SKIP） | `ui-maps` | ✅ 新 peek 行「♡」+ `new-favorite-marker.png` 可见 |
| Compose `testTag` 必须以 `resource-id` 暴露 | `ui-maps` | ✅ 已落地（`RootScreen.kt` 根部 `semantics { testTagsAsResourceId = true }`），设备已复验：全选择器可解析 |
| `lintDebug` 3 条 deprecated 警告（`Icons.Filled.DirectionsWalk/Run/Bike`） | `ui-maps` | 不阻塞，建议顺手修 |
| 途经点坐标值在 393dp 下显示为 `31.23040, 1…` | `route-planner` | ✅ 已在 16:57 构建修掉（标签规范成 4 位小数短式 `31.2304, 121.4737`） |
| `:app:testDebugUnitTest` 曾红（`RouteClientTest` connector） | `route-planner` | ✅ 已修，CI 绿 |

## 10. 变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-01 | 建立本表；新增检查 `0`、`12`–`25`（`e2e/lib/checks_ui.ps1`、`checks_release.ps1`）与 `e2e/screenshot.ps1`、`ui_gesture.ps1`、`capture_screens.ps1`、`route_trace.ps1`、`final_pass.ps1`、`lint.ps1`；CI 增加 harness lint、`:app:assembleRelease` 与发布件检查，并让 `:app:testDebugUnitTest` 成为硬门禁。检查点两轮实测后填入 §8/§8a 的数字与 ③ 的 FAIL 证据。 |

## 本轮证据快照（framework-only，2026-11-02 00:30）

作用域只有 `android`（system_server）+ `com.android.phone`；目标 app 进程零 hook。

| 检查 | 状态 | 证据（可复现） |
|---|---|---|
| `0-config-hygiene` | PASS | `e2e/out/report.json`（verifier 判据） |
| `1-framework-spoof` | PASS | `providers=passive,network,fused,gps mock=False at 31.230416,121.473701` |
| `2-fast-switch` | PASS | 切点 ~1.87 s 到位 |
| `3-joystick` | PASS | 引擎 tick：`moved 3.56 m / 1.08 s = 3.3 m/s`，方位 0° |
| `4-speed-levels` | PASS | WALK 1.40 · RUN 3.30 · BIKE 5.50 · DRIVE 13.90 · PLANE 230.00 · CUSTOM 7.50 m/s，ratio 全 1.00 |
| `6-cells` | **PASS（本轮新）** | `A ci=107084627/tac=46625 -> C ci=129538244/tac=50274`，`e2e/out/report-6-cells-lead.json` |
| `7-wifi` | **PASS（本轮新）** | `wifi#getScanResults -> 5 synthetic APs for uid …`，换位置 5→6 APs `changed=True` |
| `23-module-integrity` | PASS | `assets/xposed_init -> XposedEntry`，dex 内含 hook 类 |
| `24-apk-size` | PASS | release **6.24 MiB**（预算 7.5 MB，本轮从 9.95 MiB 降下来） |
| `8-steps` | FAIL（缺口已收窄命名） | 引擎侧 0→9→20→30 @120bpm 已实测；`sendEvents` hooked、`injected=27`，但 app 未收到；下一步是 handle 身份（0xbf/0xc0） |
| `9-gnss` | 待复测 | 闸门修复已实测 `syntheticPassed=50 / realWithheld=47`；harness 截断解析已修，等本轮复测 |
| `10-independent` | FAIL（真实 bug） | `pkg=UNRESOLVED (independent)` 投递可达非白名单包 |
| `11-favorites` | 待 UI 复测 | 收藏入口与列表已重做 |

**方法学（吃过亏的两条）**
1. 速率/位移一律用引擎自己的 `tick … speed=… moved=…` 或 `fw_status`，不要用 verifier 的稀疏定位采样（后台时 8 s 只有 2–8 个样本）。
2. logcat 单行上限约 4 KB：`VERIFY` 行超长会被截断，harness 必须能从中抢救标量（本轮已修 `e2e/lib/verifier.ps1`）。

## 本轮最终结果（framework-only，2026-11-02 02:15）

作用域：`android`（system_server）+ `com.android.phone`；**任何目标 app 进程零 hook**。

| 检查 | 结果 | 证据 |
|---|---|---|
| 0-config-hygiene | PASS | verifier 判据 |
| 1-framework-spoof | PASS | `providers=passive,network,fused,gps mock=False` |
| 2-fast-switch | PASS | `~1860 ms` |
| 3-joystick | PASS | `3.55 m / 1.08 s = 3.3 m/s`，方位 0° |
| 4-speed-levels | PASS | 六档 1.40 / 3.30 / 5.50 / 13.90 / 230.00 / 7.50 m/s，ratio 全 1.00 |
| 5-route | PASS | `advanced 71.5 m in 11.47 s`；暂停漂移 0 m；恢复 32.47 m |
| 6-cells | PASS | `A ci=107084627/tac=46625 → C ci=129538244/tac=50274` |
| 7-wifi | PASS | `A 5 APs → C 6 APs changed=True` |
| 8-steps | PASS | `counter delta=30 over 2 samples (monotonic)` |
| 9-gnss | PASS | `satellites=51 usedInFix=7 maxCn0=43.869 samples=9` |
| 10-independent | PASS | `outside-whitelist fake=False；inside-whitelist reached=True` |
| 11-favorites | PASS | `共 1 个收藏, 1 row(s)`（4 个收藏时同样 PASS） |
| 23-module-integrity | PASS | `xposed_init → XposedEntry`，dex 内 5 个 hook 类 |
| 24-apk-size | PASS | release **6.24 MiB**（预算 7.5 MB） |

**已知限制（诚实记录）**：一次性跑完整矩阵时会出现**检查间串扰**——上一项检查留下的引擎/verifier 状态
会污染下一项（实测：4-speed 把起步静止 tick 平均进去后 ratio 恰好 0.50；8-steps 与 10-independent
只在合并运行时失败）。每一项**单独运行**都 PASS。下一轮的 harness 任务是让 4-speed 用**中位 tick 速度**
而不是窗口平均，并在每项检查前重置引擎状态。

**本轮修掉的三个真实产品缺陷**
1. 基站状态通道写侧（Context NPE + 先提交去重键 + 只记一次日志）导致整个 boot 不发布 → `6-cells`。
2. 步频模拟：app 注册的是**唤醒**计数器 `0xc0=192`，只推 `0xbf=191` 被 per-connection 过滤 → `8-steps`。
3. 独立模拟：`pass through` 的"真实位置"其实是**我们改写过的假位置**（报告路径重写了 provider 缓存），
   非白名单 app 因此照收假点 → `10-independent`；改为替换成捕获到的真实 fix 或丢弃。
