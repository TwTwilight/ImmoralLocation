<h1 align="center">
  <img src="assets/icon.png" width="76" alt="图标" align="middle" />
  ImmoralLocation
</h1>

<p align="center"><img src="assets/01-main.png" width="280" alt="主界面"></p>

<table align="center">
  <tr>
    <td align="center"><img src="assets/02-search.png" width="340" alt="搜索"></td>
    <td align="center"><img src="assets/03-layers.png" width="340" alt="图层"></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/04-joystick.png" width="340" alt="摇杆"></td>
    <td align="center"><img src="assets/05-route.png" width="340" alt="路线规划"></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/06-tools.png" width="340" alt="工具面板"></td>
    <td align="center"><img src="assets/07-place.png" width="340" alt="落针卡片"></td>
  </tr>
  <tr>
    <td align="center"><img src="assets/08-tap.png" width="340" alt="单击小卡"></td>
    <td align="center"><img src="assets/09-dark.png" width="340" alt="深色模式"></td>
  </tr>
</table>

<h3 align="center">LSPosed 位置模拟器 · Android 8.1+</h3>

<p align="center">
  <a href="https://github.com/TwTwilight/ImmoralLocation/releases/latest"><img src="https://img.shields.io/badge/APK-6750A4?style=for-the-badge&logo=android&logoColor=white" alt="下载 APK"></a>
</p>

## 构建

```bash
./gradlew :app:assembleRelease
```

| 想要什么 | 加这个参数 |
|---|---|
| 带上 32 位 | `-PimmoralAbis=arm64-v8a,armeabi-v7a` |
| 每个 ABI 单独出包 | `-PimmoralAbiSplits=true` |
| 保留高德 的全部资产（换真实 AMap key / 排查地图） | `-PimmoralAmapAssets=true` |
| 单独再丢一组高德 资产做 A/B（实验用） | `-PimmoralAmapDropAssets=map_assets` |

release 默认丢掉四组高德 资产（`assets/cfg`、`assets/map_custom`、`offlinemapv4.png`、
`amap_resource1_0_0.png`，合计 −1.95 MB），**每一组都在设备上单独验证过**：地图与保留时同视口一致。
`assets/ae/res.zip` 与 `assets/map_assets` 是硬依赖（去掉即黑屏/空白），**必须留**。
⚠️ 缺 `map_assets` 时 `amap map loaded` 这行日志照样会打印 —— 判据只能是帧，不能是日志。
详见 [`docs/ROUND-NOTES.md` §7](docs/ROUND-NOTES.md) 与 [`docs/STEALTH.md` §4.1](docs/STEALTH.md)。
