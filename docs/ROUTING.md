# 路径规划（route planning）实现说明

> 本文说明 `route-planner` 工作面交付的路径规划链路：数据模型、三档路由后端、坐标系约定、
> 途经点与分段、加密预算、播放，以及如何在离线状态下复现 OSRM 抓包测试。
> 契约来源：`docs/UIUX-REWORK.md` §6 与 `docs/CONTRACTS.md`。

## 1. 结构总览

```
ui/route/RoutePlannerHost.kt          M3 全高 sheet 内容（起点/途经点/终点、出行方式、结果卡、搜索页）
        │  只读 StateFlow、只调方法
ui/route/RoutePlannerController.kt    表单状态 + 规划/播放/保存/清除 + 搜索会话
        │  client.plan(RouteRequest)（Dispatchers.IO）
net/RouteClient.kt                    三档后端编排：高德 → OSRM → 直线；把失败原因作为数据返回
        ├── net/AmapRouteService.kt   高德 RouteSearch SDK（唯一说 GCJ-02 的地方）
        └── net/OsrmRouteService.kt   公开 OSRM（WGS-84，单请求多途经点）
core/route/RouteGeometry.kt           纯 Kotlin 模型 + 去重/加密/拼段/降级/进度采样
core/route/SavedRoute.kt              已保存路线（只存停靠点，不存几何）
data/SettingsStore.kt                 routePath / routeOrigin / routeDestination / routeWaypoints / routeProfile
```

## 2. 坐标系约定（"路线画偏 500 米" 的经典来源）

整个 app **只有一处**做 GCJ-02 转换：地图后端内部。

| 层 | 坐标系 | 谁负责转换 |
|---|---|---|
| 引擎 / `EngineState` / `RoutePlan` / `SettingsStore` / `RouteClient` 的输入输出 | **WGS-84** | 不转换 |
| `MapBackend`（`MapHost` / `AmapHost`）接口 | **WGS-84** | 内部转到瓦片/地图 SDK 的 GCJ-02 |
| 高德 `AMap` 地图 SDK、高德 `RouteSearch` 返回的 polyline、高德 POI 坐标 | **GCJ-02** | `AmapHost` / `AmapRouteService` / `AmapPoiSearch` 用 `map/Gcj02.kt` 转换 |
| OSRM / OpenStreetMap 数据 | **WGS-84** | 不转换 |

具体规则：

* `AmapRouteService` 里 `toGcj(point)`（请求前）与 `toWgs(point)`（解析后）逐点转换：
  * 请求：WGS-84 起点/终点 → `Gcj02.fromWgs84` → `LatLonPoint`；
  * 响应：`WalkStep/DriveStep/RideStep.getPolyline()` 的每个点 → `Gcj02.toWgs84`。
* 中国境外（`Gcj02.inChina` 之外，见 Krasovsky 包围盒 3.86..53.55 / 73.66..135.05）转换是恒等映射，
  所以境外坐标原样通过。
* OSRM 请求用 `lon,lat`（GeoJSON 顺序）发出，响应 `geometry.coordinates` 按 `[lon, lat]` 解析，
  全程不做 GCJ 变换。
* **不要在 UI 里自己转换**：`RoutePlannerHost` / `RootScreen` 把 WGS-84 交给 `MapBackend.setRoute(...)`，
  地图后端自己转。若某天出现「polyline 整体偏 300–500 m」，先检查这条是否被破坏。

## 3. 三档后端（`net/RouteClient.kt`）

`RouteClient.plan(RouteRequest)` 依次尝试，**永远不会抛异常**，总会返回可播放的几何：

| 档 | 实现 | 适用 | 失败时的处理 |
|---|---|---|---|
| 1 | 高德 `RouteSearch`（`AmapRouteService`） | 中国大陆道路数据最佳，无需自建服务 | 鉴权错误码（10001/10002/10003/10005/…）→ 轮换身份池 `AmapPoiSearch.rotateIdentity` 重试，最多 2 次；其他错误 → 下一档 |
| 2 | 公开 OSRM `https://router.project-osrm.org/route/v1/{profile}/{lon,lat;…}` | 无 key、境外可用、一次请求带全部途经点 | 超时/非 2xx/`code != Ok`/几何为空 → 下一档 |
| 3 | 直线折线（`RouteGeometry.straightLine`） | 离线、内网、服务全部不可用 | 不可能失败（本地计算） |

* profile 映射：`WALK→walking`、`BIKE→cycling`、`DRIVE→driving`（OSRM）；高德按 profile 选
  `WalkRouteQuery` / `RideRouteQuery` / `DriveRouteQuery`。
* 失败原因以 `RouteFailure`（`NoRoute` / `AmapUnauthorized` / `AmapFailed` / `OsrmFailed` / `Other`）返回，
  **文案不写在 net 层**：`RoutePlannerController` 用 `strings_route.xml` 的 `rp_*` 组装提示。
* 降级时 `RouteResult.source == STRAIGHT_LINE`、`degraded == true`，结果卡显示
  「未取得道路路径（原因），使用直线连接」；`开始模拟` 仍然可用（§6.6：不阻断模拟）。

## 4. 途经点 → 分段

`RouteRequest(origin, destination, waypoints, profile)`：

1. `validWaypoints`：只保留 `isValidLatLon()` 的途经点，**保持用户顺序**（绝不做最近邻排序）；
2. `stops()`：`origin + waypoints… + destination`，并去掉**相邻重复点**
   （重复点产生零长度段，会让部分路由服务报错、也会让播放卡住）；
3. `legCount() = stops.size - 1`；
4. 高德每段单独请求（`WalkRouteQuery`/`RideRouteQuery` 不支持"途经点"参数，步行/骑行无法一次带
   waypoint；为让三种模式行为一致，驾车也按段请求），随后由
   `RouteGeometry.join(legs)` 拼成一条折线，**相邻段共享的顶点只保留一次**；
5. OSRM 一次请求全部 `stops`（`lon,lat;lon,lat;…`），`legs[]` 只取 `distance`/`duration`，
   整条折线直接来自 `geometry.coordinates`。

由此 `RouteResult.legs` 永远与 `stops` 一一对应，`waypointCount = legs.size - 1`。

## 5. 加密（densify）预算

* `RouteGeometry.DEFAULT_MAX_SEGMENT_METERS = 10.0`：**相邻顶点不超过 10 米**，
  即"每 ~10 米至少一个点"。20 km 驾车约 2 000 点，既平滑又不会撑爆 prefs/bridge。
* `RouteGeometry.MAX_POINTS = 20_000`：灾难性输入（如开普敦→北京）不会无限膨胀，
  超限即返回已有点（仍可播放）。
* 同时做两件净化：丢弃非法点（NaN / 越界）、丢弃相邻重复点。
* 加密使用 `GeoMath.interpolate`，经度按**短边跨越 180° 经线**插值，所以跨日界线路线不会拉出回到 0° 的假线。
* 总距离 = `max(后端上报的 legs 之和, 实际几何长度)`：后端少报时进度条不会提前到 100%。

### 5.1 停靠点吸附与连接段（endpoint connector）

路由服务返回的是**吸附到道路**的坐标：请求一个公园内部点，几何会停在最近的可行驶道路上，
可能偏离几百米（本仓库 fixture 里 北海公园 的终点吸附差 ~190 m）。而引擎是**逐点播放 polyline** 的，
如果不处理，模拟会停在公园外的路边，而不是用户选的地方。

`RouteGeometry.connectStops(polyline, stops)` 的做法：

* 首顶点强制替换为请求的 `origin`；
* 末顶点强制替换为请求的 `destination`；
* 每个中间途经点替换到**几何上离它最近的那个顶点**；
* 只替换顶点、不插入新点，所以折线拓扑与段数不变；替换发生在 `densify` **之前**，
  因此连接段也会被加密到 ≤10 m。
* 与目标点距离 ≤ `CONNECTOR_TOLERANCE_METERS`（0.5 m，低于任何道路/GPS 容差）的顶点视为已命中，不动。
* `RouteResult.connectorCount` 记录被替换的顶点数：`0` 表示后端几何本来就经过每一个停靠点，
  `>0` 表示存在"道路 → 停靠点"的连接段。`source` 仍然是真实道路来源（`AMAP`/`OSRM`），
  只有端点被钉住。
* 副作用（有意为之）：因为模拟真的要走完连接段，`distanceMeters` 可能略大于后端上报的道路距离
  （本例 2922.4 m 道路 + ~190 m 连接段 ≈ 3115.9 m），`legs` 仍保留后端上报的分段距离。

## 6. 进度采样与播放

* `RouteResult.sample(progress)` → `(GeoPoint, bearing)`，进度按**弧长**（而非点序号）插值，
  越界自动夹到 `0..1`；`remainingMeters(progress)` 给出剩余距离。
* `RouteResult.toPlan(repeat, speedMps)` → `RoutePlan`（`core/route` 既有模型），
  交给 `Command.StartRoute(plan)`。播放点就是**几何点**，所以模拟位置沿街道移动。
* `RoutePlannerController.start()` 的顺序（关键）：
  1. `settings.setRouteSpeedMps(profile.defaultSpeedMps)` — 让模拟速度与本模式一致
     （步行 1.4 / 骑行 5.5 / 驾车 13.9 m/s），否则 ETA 与模拟时长对不上；
  2. `settings.setPlannedRoute(request, result)` — 几何、起终点、途经点、profile 一次性写入；
  3. `startPlayback()` → `AppController.startRoute()`，它读取
     `settings.routePath.ifEmpty { settings.routePoints }` 并下发 `StartRoute`。
  **几何必须先落盘**，否则播放器会拿到上一份计划。
* 地图侧连线：`MapBackend.setRoute(routePath.ifEmpty { routePoints })`（`ui-maps` 的 `RootScreen`）。

## 7. 离线复现 OSRM 抓包测试

`app/src/test/resources/osrm_beijing_driving.json` 是**真实响应**（不是手写 mock）。
重新抓取（王府井 → 故宫 → 北海公园，驾车）：

```powershell
$u = 'https://router.project-osrm.org/route/v1/driving/116.397532,39.908692;116.397150,39.916345;116.383100,39.925400?overview=full&geometries=geojson&alternatives=false&steps=false'
$r = Invoke-WebRequest -Uri $u -TimeoutSec 30 -UseBasicParsing
[System.IO.File]::WriteAllText("app\src\test\resources\osrm_beijing_driving.json", $r.Content, (New-Object System.Text.UTF8Encoding($false)))
```

抓到的响应特征（2026-11 抓取）：`code=Ok`、`51` 个几何点、`2922.4` 米、`321.7` 秒、`2` 段。

离线跑测试（不联网）：

```powershell
.\gradlew.bat --offline :app:testDebugUnitTest --tests "com.immoral.location.net.RouteClientTest"
```

`RouteClientTest` 用假 `HttpFetcher` 回放该 fixture，断言：

* `source == OSRM`、`legs.size == 2` 且顺序为 王府井→故宫→北海；
* 加密后 `polyline.size > 51`，且**任意相邻点 ≤ 10 m**；
* **这条线确实是沿道路走的**：`measuredDistance > 1.05 × 直线距离`（直线距离 = 两段大圆距离之和）；
* 首末顶点与请求的 `origin`/`destination` **完全一致**（经纬度差 < 1e-9），中间途经点也出现在几何上，
  且 `connectorCount >= 1`（北海公园终点被道路吸附，必须有连接段）；
* 总距离 = 几何实测距离（道路 2922.4 m + 连接段），`legs` 之和仍等于后端上报的 2922.4 m，
  因此 `distanceMeters >= legs 之和`；
* `toPlan()` 后总长一致、进度采样有位移；
* URL 形状：`/route/v1/{profile}/lon,lat;lon,lat;…?overview=full&geometries=geojson`，途经点顺序不变。

另外还覆盖：离线/非 2xx/`NoRoute`/垃圾响应 → 退化为直线且 `degraded=true`；`origin == destination`
→ `RouteFailure.NoRoute` 且**不发出任何网络请求**；跨日界线请求距离有限且经度始终在 ±180° 内。

## 8. 已知限制

* 公开 OSRM 是"courtesy"服务：限流、可能在中国大陆不可达；失败即降级，属预期行为。
* 高德步行/骑行 query 无途经点参数 → 按段请求；途经点很多时请求数线性增长（每段一次）。
* 高德需要有效 key（`assets/amap_keys.json` + `amapkit_keys` 中的活动身份）；被拒时轮换身份，
  仍失败则走 OSRM，因此**没有 key 的设备也能规划路线**。
* 直线降级时距离/时长按 profile 默认速度估算，UI 会明确标注 `直线连接`。

## 9. 结果模型字段速查

```kotlin
RouteResult(
  polyline: List<GeoPoint>,        // WGS-84，已加密，可播放
  legs: List<RouteLeg>,            // 与 stops 一一对应
  distanceMeters: Double,          // max(后端上报, 几何实测)
  durationSeconds: Double,         // 后端上报；缺失时按 profile 速度估算
  source: RouteSource,             // AMAP | OSRM | STRAIGHT_LINE（qa-e2e 用它在验收报告里证明"道路级"）
  degraded: Boolean,               // true == 直线降级
  message: String?,                // 可选说明（UI 自行本地化）
  profile: RouteProfile,
)
```
