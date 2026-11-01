<h1 align="center">ImmoralLocation</h1>

<p align="center"><img src="assets/01-main.png" width="280" alt="主界面"></p>

<p align="center">
  <img src="assets/02-search.png" width="165" alt="搜索">
  <img src="assets/03-layers.png" width="165" alt="图层">
  <img src="assets/04-joystick.png" width="165" alt="摇杆">
  <img src="assets/05-route.png" width="165" alt="路线规划">
</p>

<p align="center">
  <img src="assets/06-tools.png" width="165" alt="工具面板">
  <img src="assets/07-place.png" width="165" alt="落针卡片">
  <img src="assets/08-tap.png" width="165" alt="单击小卡">
  <img src="assets/09-dark.png" width="165" alt="深色模式">
</p>

<h3 align="center">LSPosed 位置模拟器 · Android 8.1+</h3>

<p align="center">
  <a href="https://github.com/TwTwilight/ImmoralLocation/releases/latest"><img src="https://img.shields.io/badge/APK-6750A4?style=for-the-badge&logo=android&logoColor=white" alt="下载 APK"></a>
</p>

## 构建

```bash
./gradlew :app:assembleRelease     # 发布包；release 默认只出 arm64-v8a，并丢弃高德样式/室内包 assets/cfg
```

| 想要什么 | 加这个参数 |
|---|---|
| 带上 32 位（需要同时提供 32 位 `libimmoralhook.so` 才有意义） | `-PimmoralAbis=arm64-v8a,armeabi-v7a` |
| 每个 ABI 单独出包（`app-<abi>-release.apk`） | `-PimmoralAbiSplits=true` |
| 保留高德 的 `assets/cfg`（换真实 AMap key / 排查地图问题） | `-PimmoralAmapAssets=true` |

release 构建默认丢掉 `assets/cfg`：实测**地图渲染与保留时逐屏一致**（四臂 A/B，含一次黑屏回归），
其余高德 资产是硬依赖，不能一起删。原因、数字与证据边界见
[`docs/ROUND-NOTES.md` §7](docs/ROUND-NOTES.md) 与 [`docs/STEALTH.md` §4.1](docs/STEALTH.md)。

