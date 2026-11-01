# UI/UX 重做规范（Material You · Google-Maps 式交互）

> 本文件是**契约**：`ui-maps` / `route-planner` / `hook-loc` / `qa-e2e` 四个并行工作面都按此实现。
> 改动本文件必须先通知 Lead。
>
> 写权限（2026-11-01 修订）：
> * `ui-maps`：`app/.../ui/**`（含 `RootScreen.kt`）但**不含** `ui/AppController.kt`、`ui/route/**`；
>   以及 `app/.../map/**`、`app/.../overlay/**`、`app/src/main/res/**`。
> * `route-planner`：`core/.../core/route/**`、`app/.../net/**`、`app/.../data/**`、`app/.../ui/route/**`、
>   `res/values/strings_route.xml`。
> * `hook-loc`：`hook/**`、`native/**`、`docs/AMAP-STABILITY.md`。
> * `qa-e2e`：`e2e/**`、`.github/**`、`verifier/**`、`docs/E2E.md`、`docs/ACCEPTANCE.md`、`docs/screenshots/**`。
> * Lead：`ui/AppController.kt`、`App.kt`、`ui/MainActivity.kt`、`core/.../core/model/**`、根构建配置、README。

## 0. 用户投诉 → 必须解决的点

| # | 投诉 | 验收方式 |
|---|---|---|
| 1 | UI/UX 太差，没有设计感，要 Material You | 全部界面基于 M3 组件 + 动态取色；无自绘“土味”控件残骸；截图评审通过 |
| 2 | 摇杆方向是错的 | 摇杆默认**与屏幕/正北对齐**（上=北），跟随时在摇杆上显示朝向指示；设备实测位移方位与手指方位误差 < 15° |
| 3 | 各种地方有 toast/摇杆遮挡 | 全工程 `grep Toast` 为 0；Snackbar 固定在**顶部**（搜索条下方）；摇杆/卡片/FAB 之间无重叠 |
| 4 | 高德地图模拟不稳定，半分钟变回真实位置 | 高德前台连续 3 分钟不掉真实位置（hook 层修复 + 日志证据） |
| 5 | 地图点不 Material You | 全新 marker 体系（当前位置光环、途经点徽标、路线双描边） |
| 6 | 没有路径规划 / 模拟路径 / 途经点 | 起点-终点-多途经点规划，道路级几何，可开始模拟、可保存 |
| 7 | 不一定要摇杆，可以抄 Google Maps | 底部卡片 + 长按落针卡片 + “导航到这里”主路径，摇杆是可选工具（默认关闭） |

## 1. 信息架构

单 Activity，**一张全屏地图 + 浮动 chrome + 一个底部 sheet**，不再有常驻大色块横幅。

```
┌───────────────────────────────────────────┐
│  (安全区)                                  │
│  ╭──────────────────────────────╮  ╭───╮  │  顶部：搜索条（pill, 56dp）+ 工具头像
│  │ 🔍 搜索地点、坐标或地址        │  │ ☰ │  │
│  ╰──────────────────────────────╯  ╰───╯  │
│                                           │
│             地图（全屏、无边距）             │
│                                           │
│                                    ╭───╮  │  右侧竖排 FAB（bottom-end，位于 sheet 之上）
│                                    │图层│  │
│                                    ╰───╯  │
│                                    ╭───╮  │
│                                    │摇杆│  │
│                                    ╰───╯  │
│                                    ╭───╮  │
│                                    │定位│  │
│                                    ╰───╯  │
│  ╭─────────────────────────────────────╮  │  底部 sheet（可停靠 peek/half/full）
│  │ ▁▁                                  │  │
│  │ ● 模拟中 · 王府井 · 步行 1.4 m/s      │  │  peek 行（状态 + 速度）
│  │ [🚶][🏃][🚲][🚗][✈][⚙]               │  │  出行方式 segmented（6 个等宽格子，不裁切）
│  │ [ 暂停 ]  [ 路线 ]  [ ♡ ]            │  │  主操作行
│  ╰─────────────────────────────────────╯  │
└───────────────────────────────────────────┘
```

* 半展开（half）：工具面板（基站 / WIFI / 步数 / GNSS / 更新频率 / 独立模拟 / 收藏）
* 全展开（full）：路线规划器（`route-planner` 提供的 `RoutePlannerHost`）
* 摇杆：由右侧 FAB 开关，浮在 sheet 之上（bottom-end，距 sheet 顶 16dp），默认 **关闭**
* 长按地图：Material You “落针卡片”（地点名 / 坐标 / 距离 + 主次操作）

## 2. 设计令牌（Material You）

* 颜色：API 31+ `dynamicLightColorScheme/dynamicDarkColorScheme`；低版本用固定 seed 生成
  （seed `#3B5BA5` 取 M3 tonal palette），必须同时支持深色模式。
* 表面层级：地图之上的浮动 chrome 一律 `surfaceContainerLow` @ 0.94 alpha + 1dp outlineVariant 描边；
  FAB `surfaceContainerHigh`；激活态 `primary/onPrimary`。
  **底部 sheet**（不透明，`sheetContainer()`）：浅色 `surfaceContainerLow`，深色 `surfaceContainer`
  （深色下 `surfaceContainerLow` 与压暗的瓦片相差 <2%，只靠阴影读不出"浮起"）+ 1dp outlineVariant 描边。
* 地图压暗/调色：**渐变 scrim**，不是整屏平铺——顶部（搜索条下）浅色 10 % / 深色 30 %，中部完全透明，
  底部（sheet 之上）浅色 16 % / 深色 34 %。整屏平铺会让浅色主题发灰、地名发白（第一轮的问题）。
* 形状：搜索条/胶囊 `CircleShape`，FAB 16dp，sheet 顶部 28dp，卡片 20dp，chip 8dp。
* 间距：水平 16dp，chrome 之间 8dp，sheet 内 20dp；全部走 `WindowInsets`（状态栏/手势条）。
* 字体：M3 默认（Roboto）；坐标用 `labelSmall` + `FontFeatureSettings("tnum")`，不再作为主标题。
* 动效：sheet 停靠弹簧、FAB 随 sheet 展开淡出、摇杆出现 150ms scale-in；无自定义花哨动画。
* 图标：只用 `material-icons-extended` 里现成的，禁用 emoji 当图标。

## 3. 地图元素（marker 体系）

| 元素 | 规格 |
|---|---|
| 当前位置 | 直径 22dp `primary` 实心 + 3dp `surface` 外环 + 精度光环（半径 = 精度米数换算 px，`primary` 12%），下方 1.5dp 柔和阴影；有 bearing 时再加 28dp 扇形朝向指示 |
| 收藏点 | 12dp `tertiary` 实心圆 + 白色描边；选中时展开为 label chip（`surfaceContainerHigh`，8dp 圆角，带名称） |
| 途经点 | 22dp 圆，`primaryContainer` 底 + `onPrimaryContainer` 序号（1..n） |
| 起点 / 终点 | 起点 `primaryContainer`，终点 `errorContainer`，均带 2.5dp casing（浅色 `surface` / 深色 `onSurface`），24dp 圆 + 12sp 粗体字 + 下沉 3dp 的投影；第二轮的实测结论是 22dp/11sp 在 POI 标签上会糊掉 |
| 路线 | 外层 12dp `surface` @0.9 描边 + 内层 7dp `primary` 圆头；已走过段落用 `onSurfaceVariant` 40%（可选） |
| 阴影 | 所有 marker 用 `setShadowLayer`/低透明度椭圆代替硬边框 |

## 4. 摇杆（重做）

* 尺寸 152dp 圆盘，`surfaceContainerHighest` 78% + 8dp elevation；底盘 3dp `primary` outline。
* 拇指 44dp `primary` + `surface` 2dp 环，跟手 + 弹簧回中。
* **方向语义（关键修复）**：摇杆向量始终是“屏幕方向”（上=地图上方=正北）。engine 侧
  `followHeading` 默认改为 **false**；开启“跟随手机朝向”时：
  * 发动机仍按 heading 旋转位移方向（旧行为保留），
  * 摇杆圆盘额外绘制一个 `-heading` 旋转的刻度环 + 北向 N 标记，让用户看得见“上=手机朝向”，
    UI 不得让用户误以为上=北。
* 松手 = 停止（`releaseJoystick`），并将 `MoveMode` 保持在 JOYSTICK。
* 摇杆不得遮挡：搜索条、FAB、sheet、Snackbar。Snackbar 永远在顶部，故不冲突。

## 5. 提示（禁止 Toast）

* 全工程删除 `android.widget.Toast`；统一 `SnackbarHost`：
  * 位置：地图 Box 的 `TopCenter`，`padding(top = 搜索条高度 + 8dp)`；
  * 样式：`inverseSurface`、16dp 圆角、最多 2 行、`duration = Short`，同文案 1.5s 内去重；
  * 与 sheet/FAB/摇杆区域零重叠（顶部区域是唯一“闲置区”）。
* 命令失败等错误提示用同一个 Snackbar，可带一个动作按钮（“重试”）。

## 6. 路线规划（`route-planner` 负责实现，`ui-maps` 只提供入口）

流程（Google Maps 式）：

1. peek 行点「路线」→ sheet 全展开 `RoutePlannerHost`。
2. 字段：`起点`（默认当前位置）/ `终点` / 若干 `途经点`（可增删排序）；点字段进入搜索页
   （复用现有 `PlaceSearchClient` + 最近使用）。
3. 出行方式 segmented：步行 / 骑行 / 驾车 → 触发路径计算。
4. 结果卡：总距离、预计时长、途经点数量；地图上画出道路级 polyline + 起终点/途经点 marker。
5. 操作：`开始模拟`（把几何交给 engine 播放）、`保存为收藏`、`清除`。
6. 网络失败：退化为“直连折线”并明确提示“未取得道路路径，使用直线连接”，不阻断模拟。

数据流契约：

```kotlin
// app/.../data/SettingsStore.kt（route-planner 拥有）
val routePath: StateFlow<List<GeoPoint>>   // 道路级几何，空则回退 routePoints
// app/.../ui/route/RoutePlannerHost.kt（route-planner 拥有，ui-maps 只做入口调用）
@Composable fun RoutePlannerHost(controller: RoutePlannerController, onDismiss: () -> Unit)
// 地图绘制：MapBackend.setRoute(points) 由 ui-maps 决定用 routePath ?: routePoints
```

## 7. 高德稳定性（`hook-loc` 负责）

目标：高德地图（`com.autonavi.minimap`）前台连续 ≥3 分钟、含地图拖动/缩放/重新定位，位置不得回落真实坐标。

要求：
* 先用日志定位“半分钟回真实”的真实原因（本地定位 SDK 绕过 framework？provider 超时？独立模拟丢投递？）
* 修复必须在 `hook/**`（必要时 `native/**`）内完成，不得改动 app 侧语义；
* 提供 `docs/AMAP-STABILITY.md`：现象、根因（附日志片段）、修复、回归证据。

## 8. 交付纪律

* 只动自己 scope 的文件；跨 scope 一律先发消息给 Lead。
* 每个工作面完成时：`./gradlew --offline :core:test :app:testDebugUnitTest`（或各自相关模块）通过 +
  在 `docs/` 留证据；`qa-e2e` 负责最后一轮设备验收。
* 全部新增文案进 `app/src/main/res/values/strings.xml`（`ui-maps` 独占），
  `route-planner` 只新增 `values/strings_route.xml`，不要改别人的 key。

## 9. 第二轮 polish（ui-polish，2026-11-01）

### 9.1 收藏入口（修 `11-favorites`，也是产品缺陷）

第一轮把收藏列表放在 `工具 → 滚到底 → 管理` 三层之后，默认面上唯一提到"收藏"的控件是 peek 行的 ♡ ——
它**保存/删除**而不是打开列表。验收脚本按可见文案找不到收藏面，退化到按 content-desc 匹配，正好点中 ♡，
于是"打开收藏"变成了"删除一个收藏"（并弹出 `已删除收藏` 的 snackbar），`共 N 个收藏` 自然读不到。

现行契约（e2e 可依赖）：

| 位置 | 文案 / tag | 行为 |
|---|---|---|
| peek 卡第二行右侧 | 胶囊 chip：书签图标 + `收藏`（`R.string.tools_tab_favorites`）+ 数量；tag `e2e.peek.favorites` | 打开收藏列表；数量为 0 时不显示数字 |
| 工具面板 | `收藏` 标题 + `共 N 个收藏` 正文 + `管理` 按钮 | `管理` 恒在（空列表也可进列表页） |
| 收藏列表页 | 标题 `已保存的地点`、正文 `共 N 个收藏`、每行 `名称 / lat, lon / 距离` + `收藏当前位置` 主按钮；tag `e2e.favorites` | 行内 `lat, lon` 是 `labelSmall` + tnum，也是 `11b` 点击回归的目标 |

* 列表行的名称**不得**以 `收藏地点` 开头：`11b` 用 `^收藏地点|<lat, lon>` 找目标行，
  行名与页面标题撞车会让它点到行名而不是坐标。`FavoritesStore` 的占位名 `收藏地点 N` 由
  `FavoriteText.displayName()` 在**显示层**映射为 `收藏点 N`（不改存储值）。
* 收藏列表 sheet 高度 92 %（`fillMaxHeight(0.92f)`），地图仍可见。

### 9.2 peek 卡信息层级（实测）

| 行 | 内容 | 规格 |
|---|---|---|
| 手柄 | 32×4dp 胶囊，10dp 上下内边距 | 点按 = 上移一档 |
| 状态行 | 8dp 活点（运行中 1100ms alpha 1→0.42 呼吸）+ `模拟中` titleMedium/SemiBold + `  ·  步行 1.4 m/s` bodyMedium/onSurfaceVariant | 整行可点 → 状态详情；模块未激活时右侧出现 `PeekHintChip`（errorContainer 胶囊），不再是一行红字 |
| 元信息行 | 坐标 labelSmall + tnum（weight 1f）+ `收藏 N` chip（32dp 高，secondaryContainer） | 坐标不再是标题，也不再单独占一行 |
| 出行方式 | 6 等宽格，容器 `surfaceContainerHigh`@0.72 + 1dp outlineVariant@0.45，圆角 16dp，格高 44dp，圆角 12dp；选中格 `primary`，颜色 120ms 过渡 | 六格等宽、永不裁切（`14b-speed-row`） |
| 主操作 | `暂停/开始`(Button) + `路线`(FilledTonalButton) + ♡(FilledTonalIconButton) | **三者等高 48dp** + 8dp 间距；♡ 带 1dp 描边，已收藏时 `tertiaryContainer`（不再只靠图标填充区分） |

### 9.3 动效

* sheet 停靠：`spring(dampingRatio = 0.88, StiffnessMediumLow)`（第一轮已有，保留）；
* FAB 栈：随 sheet 展开 `alpha 1→0` 同时下沉 10dp（160ms），不是单纯闪没；
* 摇杆：`spring(dampingRatio = 0.68, StiffnessMediumLow)` scale-in 0.82→1（≈180ms），退出 120ms；
* 落针卡 / 轻点 chip：`fadeIn(150) + slideInVertically(180, it/3)`，退出 `fadeOut(120)`；
* 出行方式选中格：`animateColorAsState(tween(120))`。


## 10. 表面规则（surface rules）— 所有者「灾难级别」评审后的唯一真源

> 这一节是**规则**，不是描述。任何新面板 / 新按钮先对照本节，再写代码。
> 评审给出的判据只有五类：死空间、禁用噪音、含糊可点、角色不一致、缺标签。

### 10.1 容器角色（谁用哪个 tonal role）

| 表面 | 容器 | 说明 |
|---|---|---|
| 底部 sheet | `sheetContainer()`（浅 `surfaceContainerLow` / 深 `surfaceContainer`）+ 1dp `outlineVariant` 描边 | 不使用 `surface`：浅色下会在 sheet 里开一个"白洞" |
| sheet 内普通卡片 | `surfaceContainerHigh` + 1dp hairline，**elevation 0** | 描边即边界，阴影留给浮在地图上的 chrome |
| sheet 内焦点卡（路线结果） | `surfaceContainerHighest` + hairline | 只允许一张焦点卡 |
| 浮在地图上的 chrome（搜索条、工具胶囊、FAB 组、落针卡） | `surfaceContainerLow` @0.94 + 1dp hairline + elevation 3–6dp | 唯一允许投影的一层 |
| 地图压暗 | 渐变 scrim（见 §2） | 不用整屏平铺 |

### 10.2 唯一动作层级（action hierarchy）

1. **主操作**：每个表面**最多一个** `Button`（filled），高 48dp，能全宽就全宽。
2. **次操作**：`FilledTonalButton` 48dp；贴在卡片上的图标按钮用 `FilledTonalIconButton` 48dp + 1dp `outlineVariant` 描边。
3. **破坏性操作**：`FilledTonalIconButton`，容器 `errorContainer`、图标 `onErrorContainer`，永远是图标按钮而不是文字主按钮。
4. **列表行内操作**（重命名/删除/上移下移）：裸 `IconButton`，只带 contentDescription，可与 48dp 行共存。
5. **禁用即隐藏**：不存在"灰着的主按钮"。当前状态不可能执行的动作**不渲染**，条件成立时再出现（progressive disclosure）。唯一例外是等待期进度条。
6. 所有控件 48dp 高（图标按钮正方形 48×48），同一行内不允许 40dp 与 48dp 混排。

### 10.3 一个控件一个职责（one control, one job）

* **开关**用实心/着色图标按钮表示（例：♡ = 收藏/取消收藏当前位置，唯一带 `e2e.action.favorite` 的控件）。
* **跳转**用 `primary` 文字 + 尾部 chevron、**无容器**表示（例：peek 第二行 `收藏 N ›` 打开收藏列表）。
* 二者**不得合并成一个 chip**：第一轮把"打开列表"做成 tonal chip，看起来像第二个状态开关，这正是 S1 的缺陷来源。
* 同一 glyph 不得表达两件事（第六个出行格改用 `Speed` 表盘，不再用 `Tune` 齿轮，避免与工具/图层撞脸）。

### 10.4 字阶（type scale，唯一来源 `ui/components/SheetText.kt`）

| 角色 | 组件 | 样式 |
|---|---|---|
| 标题 | `SheetTitle` | `titleLarge` / SemiBold |
| 标签（字段名、分组名、统计名） | `SheetLabel` | `labelMedium` / onSurfaceVariant |
| 值（地点名、坐标以外的数值、选项名） | `SheetValue` | `titleMedium` |
| 辅助说明 | `SheetSupporting` | `bodySmall` / onSurfaceVariant |
| 提示（不是控件） | `SheetHint` | bodySmall + info 图标，**无容器** |

禁止：`headlineSmall` 当标题、`bodyLarge` 当字段值、`labelLarge` 当分组标签、把提示塞进填充卡片（会读成禁用按钮）。

### 10.5 顶部 chrome 与右缘

* 顶栏只允许 **56dp 胶囊**：搜索胶囊 + 它的"孪生兄弟"工具胶囊（同高、同圆角、同容器、同描边、**带可见文字标签**）。顶栏禁止出现方形按钮。
* 右缘浮件只允许 **一个分组托盘**（`ChromeFabGroup`：20dp 圆角 tonal 盘 + 内嵌分隔线），里面的 48dp 按钮保留原 `e2e.fab.*` tag 与 bounds。散落的多个方块按钮不再允许。

### 10.6 sheet 几何（不许有死空间）

* **每个 sheet 都要贴合内容**，上限为该 sheet 可用高度的 92%：
  * PEEK：`PeekPanel` 自身 `onSizeChanged` 上报（第一轮已有）；
  * FULL：`RoutePlannerHost` 通过 `onContentHeightChange` 上报内容高度，`MapScreen` 用 `min(内容 + 折叠状态行 + 手势条, 屏幕上限)`；
  * 模态 sheet（收藏）：`BoxWithConstraints` + `heightIn(max = 92%)`。
* 内容短时**不得**出现"下半屏空容器"；内容长时内部滚动，不拉高 sheet。
* 主 CTA 直接排在内容之后，不做"钉底 CTA + 上方空洞"。

### 10.7 缺标签（missing label）

* 主操作/次操作**必须有可见文字**；图标按钮必须有 contentDescription（`Get-AppString` 可解析的 string 资源）。
* 摇杆：盘面右下角…（见 §4）现在带一个 36dp ✕ 收起按钮（`e2e.joystick.close`），不再是"只能靠 FAB 再点一次"。


### 10.8 R6 — 地图相机必须让主体可见（subject visible）

* sheet 存在时，模拟点 **不得**落在屏幕几何中心：`MapScreen` 把相机偏移设为 `sheetPx / 2`
  （`MapBackend.setSubjectOffsetY`；osmdroid 用 `setMapCenterOffset`，高德用 `setPointToCenter`），
  使主体落在"sheet 上方可见带"的中心。PEEK / HALF / FULL 三档都适用。
* 这条规则以前只存在于口头：第二轮的 FULL 帧里模拟点正好被 sheet 顶边切成两半（S4 的光环等于白做）。
* 验收：任何包含 sheet 的截图里，模拟点必须完整可见，且其外环不被 sheet 边缘裁切。
