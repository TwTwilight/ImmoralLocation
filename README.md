<h1 align="center">ImmoralLocation</h1>

<table align="center">
  <tr>
    <td><img src="assets/01-main.png" alt="主界面"></td>
    <td><img src="assets/02-search.png" alt="搜索"></td>
    <td><img src="assets/03-layers.png" alt="图层"></td>
  </tr>
  <tr>
    <td><img src="assets/04-joystick.png" alt="摇杆"></td>
    <td><img src="assets/05-route.png" alt="路线规划"></td>
    <td><img src="assets/06-tools.png" alt="工具面板"></td>
  </tr>
  <tr>
    <td><img src="assets/07-place.png" alt="落针卡片"></td>
    <td><img src="assets/08-tap.png" alt="单击小卡"></td>
    <td><img src="assets/09-dark.png" alt="深色模式"></td>
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
| 保留高德 `assets/cfg` | `-PimmoralAmapAssets=true` |
