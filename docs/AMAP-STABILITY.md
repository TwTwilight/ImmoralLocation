# 高德地图 reverts to the real location — analysis and fix

> **2026-11-01 后期变更（先读这一段）**：按产品负责人指示，**client 层已被整体删除**——模块现在是
> 纯 framework（`hook/client/**`、`ClientInstaller`、`ClientMirror`、`EngineMirror` 全部移除，
> LSPosed 作用域只剩 `system`）。本文档 §5.2 及其后所有提到 `WifiClientHooks` / `ClientMirror` /
> `clientSpoofingAllowed` 的段落都是**历史记录**，描述的是已经不存在、也不再参与任何行为的代码；
> 保留它们是因为它们记录了排查过程与那条"client 层可能引发反效果"的假设（§11）。当前行为以 §11
> 的对照结论与 `README.md` 为准。

Status: **行为不再复现，成因未隔离（见 §11）。** framework 层现在扣留一切定位 SDK 可能用来反推真实
位置的信号（GNSS 真值、WiFi 身份、基站身份、真实 fix）；§10 记录六次"保持模拟位置"的运行，§11 记录
用**不含本轮任何 hook 改动**的 pre-fix 构建同样保持 ⇒ 那个"几秒后回到真实位置"的现象今天无法复现，
且**不能**归因于本轮任何一条 hook。

Owner: `hook-loc`. Files touched: `hook/…/framework/GnssHooks.kt`, `hook/…/framework/WifiHooks.kt`,
`hook/…/client/{WifiClientHooks,LocationClientHooks,TelephonyClientHooks,ClientMirror}.kt`,
`hook/harness/amap_verify.ps1`, `hook/README.md`, this document. `core/`, `app/`, `e2e/` untouched.


---

## 1. The complaint

> 高德地图 (com.autonavi.minimap) shows the simulated position for about 30 seconds after launch and
> then jumps back to the real location.

Reproduced on the test device (Redmi `23049RAD8C`, Android 15 / SDK 35, HyperOS, LSPosed, module
`com.immoral.location.debug`), build `490ea3b`-era debug APK.

Engine configuration used for the reproduction (this is the *default* configuration — all the
simulation toggles off, which is what a user has before touching anything):

```
{"cmd":"SetConfig","config":{"mode":"STATIC","speed":"WALK","updateIntervalMs":1000,
 "independentMode":false,"cellSimulation":false,"wifiSimulation":false,
 "gpsSignalSimulation":false,"stepSimulation":false}}
{"cmd":"SetPosition","point":{"lat":39.9087,"lon":116.3975,"alt":50.0}}   # 天安门 / Beijing
{"cmd":"Start"}
```

The device's **real** position during the whole investigation: 杭州 建国北路/环城北路 area
(≈30.29 N, 120.16 E) — 1100 km away from the simulated point, so the two are impossible to confuse
on a screenshot.

## 2. Reproduction (with timestamps)

Cold start of 高德 while the engine is already running. `t=0` is the `am start` at 14:19:21.

| t | screenshot | map centre |
|---|---|---|
| +4 s | [10_cold_t4.png](amap-evidence/10_cold_t4.png) | **天安门, Beijing** (simulated ✅) |
| +16 s | [10_cold_t16.png](amap-evidence/10_cold_t16.png) | **杭州 建国北路** (real ❌) |
| +32 s | [10_cold_t32.png](amap-evidence/10_cold_t32.png) | 杭州 (real ❌) |
| +75 s | `10_cold_t75.png` | 杭州 (real ❌) |

So the revert happens **between +4 s and +16 s** on this device. The "≈30 s" in the report is the
same phenomenon at a different network latency — the window is set by the round trip to the
locator's cloud service, not by any timeout in our engine.

Note that 高德 does not auto-follow location updates once the user has panned the map; on a cold
start it centres once on the first fix it trusts (ours → 天安门) and re-centres when its own locator
"corrects" it (real → 杭州). That re-centre is what the user perceives as the revert.

## 3. What it is *not* (ruled out with evidence)

All of these were checked during the same run; each is a plausible hypothesis that turned out to be
wrong, and each would have needed a different fix.

* **The module was not running.** True *before* the investigation started (zero `[ImmoralLocation]`
  lines because LSPosed's `modules.apk_path` was stale after an earlier install). Fixed by
  `e2e/activate_module.ps1` + reboot; afterwards `EngineRuntime booted in 2506`, the ticker runs at
  1 Hz and `framework/location` reports all hook targets resolved. Everything below is from that
  state.
* **Framework delivery / listener registration.** `dumpsys location` shows 高德 holding a live `gps`
  registration and receiving our fix once per second:
  ```
  gps provider:
    service: ProviderRequest[@+1s0ms, HIGH_ACCURACY, WorkSource{10264 com.autonavi.minimap}]
    listeners:
      10264/com.autonavi.minimap/16B09EE9 Request[@+1s0ms HIGH_ACCURACY, WorkSource{10264 com.autonavi.minimap}]
    last location=Location[gps 39.908700,116.397500 hAcc=5.0 et=+15m37s152ms alt=50.0 ...
                    {Bundle[{immoral.stepSim=false, ..., immoral.ts=1793513862811, immoral.steps=0}]}]
    ...
    gps:      10264/com.autonavi.minimap: min/max interval = 0s/1s, ... locations = 104
  ```
  i.e. AMap received ~104 injections in 2m14s. The listener is registered, our fix is the provider's
  last-known location, and it carries the `immoral.*` state bus extras.
* **A real fix leaking past the replacement hook.** The real GNSS report is replaced, not forwarded:
  ```
  14:19:32.884 2506 7718 [ImmoralLocation] report path replaced via ALP#reportLocation
  14:19:32.887 2506 7718 [ImmoralLocation] report path replaced via onReportLocation
  ```
* **Stale `lastKnownLocation`.** AMap polls `getLastKnownLocation("gps")` roughly every 30 s; every
  poll is answered with the synthetic fix, not with a stale or real one:
  ```
  14:19:48.027 client getLastKnownLocation(gps) answered
  14:20:18.677 client getLastKnownLocation(gps) answered
  14:22:14.833 client getLastKnownLocation(gps) answered
  14:19:27.876 last-location answered via LPM#getLastLocationUnsafe as fused
  ```
* **A stalling ticker.** It never stalls: `tick dt≈1050ms interval=1000 … running=true` for the whole
  run (appliedTicks 6 → 391).
* **Independent mode / a dropped listener.** `independentMode=false` in this run, so no delivery is
  filtered; the filter lines (`independent: dropped delivery to …`) do not appear at all.
* **A second, un-intercepted `requestLocationUpdates` variant.** AMap registers `gps` (and `passive`)
  only; both are hooked and both receive injections.

## 4. Root cause

**高德 resolves the real position with its own WiFi-fingerprint cloud positioning, inside its own
process.** The BSSID list never passes through `LocationManagerService`, so no framework hook can
see or replace it.

Decisive A/B test — same APK, same config, same simulated point; the only thing that changes is the
WiFi radio:

| | WiFi ON (default) | WiFi OFF (`svc wifi disable`) |
|---|---|---|
| +4 s | 天安门 (simulated) | 天安门 (simulated) |
| +12 s | — | 天安门 (simulated) |
| +16 s | **杭州 (real)** | 天安门 (simulated) |
| +20 s | 杭州 | 天安门 — [20_nowifi_t20.png](amap-evidence/20_nowifi_t20.png) |
| +45 s | 杭州 | 天安门 — [20_nowifi_t45.png](amap-evidence/20_nowifi_t45.png) |

With the radio off (no scan results, no connected BSSID) AMap has nothing to fingerprint, falls back
to `LocationManager`, and stays on the simulated position for the whole window. That isolates the
leak to the WiFi identity, and to nothing else.

> **Correction after the first fix attempt (t+44 s, see §5.3).** The A/B above proves the leak is
> **network-dependent**, but it does *not* by itself prove it is the BSSID *list*: switching the radio
> off also removes the device's internet path to 高德's servers, so a pure IP-geolocation answer would
> look identical. The follow-up run with `wifiSimulation=false` redaction in place showed the real
> mistake precisely: the redaction *did* fire (`wifi identity redacted … (scanResults=0)`) but only
> from t+44 s, because the client mirror had no state yet when 高德 made its first, decisive call. The
> discriminator between "fingerprint" and "IP" is still open and is the next experiment:
> `adb shell svc wifi disable && adb shell svc data disable` (no network at all) → if 高德 stays on the
> simulated point, the channel needed the network; if it still shows the real city, the answer is a
> locally cached fix and redaction can never be enough.

### Why the module did not already cover it

The client layer inside the app process **does** hook the WiFi surface, but asymmetrically:

| Surface | with `cellSimulation` / `wifiSimulation` **on** | with the toggle **off** (= default) |
|---|---|---|
| `TelephonyManager.getAllCellInfo` etc. | synthetic cells | **real cells suppressed** (`cells: no state yet -> suppressed real cells`) |
| `WifiManager.getScanResults` | synthetic APs | **real APs passed through** ← the leak |

`TelephonyClientHooks` already implements the "never answer with real data" policy
(`hook/README.md`, safety invariant 5). `WifiClientHooks` simply returned early when the toggle was
off, which handed AMap's locator the raw BSSID list of the room the phone is actually in. The same
omission applies to `WifiInfo.getBSSID()/getSSID()` (the connected AP is itself a fingerprint).

This is not AMap-specific: any app that does fingerprint positioning (百度, 腾讯, Google and every
locator SDK shipped inside them) has the same escape hatch.

### Surfaces an in-app locator can read (audit)

A location SDK runs inside the app's own process and can reach data that never crosses
`LocationManagerService`. This is the complete list of what this device exposes and where we stand:

| Surface (read inside the scoped process) | Covered by | Default config |
|---|---|---|
| `LocationManager` updates / `getLastKnownLocation` / `getCurrentLocation` | framework hooks + client fallback | ✅ synthetic |
| `GnssStatus` / NMEA / satellite count | framework `GnssHooks` + `GnssClientHooks` | ✅ |
| `TelephonyManager` cell info / cell location | `TelephonyClientHooks` | ✅ real cells suppressed |
| `WifiManager.getScanResults` (BSSID fingerprint) | `WifiClientHooks` | ❌ → **fixed in this change** |
| `WifiInfo` BSSID/SSID (connected AP fingerprint) | `WifiClientHooks` | ❌ → **fixed in this change** |
| `WifiManager.getConfiguredNetworks` (saved SSIDs) | `WifiClientHooks` | ❌ → **fixed in this change** |
| Sensors / step counter | `SensorHooks` + native `SensorService` hook | ✅ |
| **Public-IP geolocation** (the SDK's own HTTPS request) | — (not interceptable at this layer) | ⚠️ out of scope, see §6 |
| The app's own on-disk cache of a previously seen real fix | — | ⚠️ only replays old data; invalidated by the first trusted fix |

## 5. The fix

> **Architecture note (product constraint).** The shipped fix is **framework-only**: it lives in
> `hook/…/framework/**` and runs in `system_server`, so 高德地图 (and any other app) is *not* in the
> LSPosed scope — 「只勾 framework，不侵入目标 app」. The client-layer change described in §5.3 is kept
> as a documented **fallback** for ROMs where the framework wifi/GNSS seams are missing; it is not
> required for the fix, and the acceptance run for ③ uses `-Packages android -Prune` so a PASS cannot
> be attributed to it.

### 5.1 Framework layer (shipped)

`hook/…/framework/WifiHooks.kt` — **the seam that was silently dead**. On this ROM the wifi stack is
an APEX module, so `com.android.server.wifi.WifiServiceImpl` is *not* reachable through the
system-server class loader at hook time and the module logged, for months of runs:

```
hook MISS com.android.server.wifi.WifiServiceImpl (class not found)
hook MISS com.android.server.wifi.ScanRequestProxy (class not found)
==== framework/wifi: 0 hook targets resolved, 2 missed ====
```

The fix resolves the service **by instance** instead of by class name:

```
ServiceManager.getService("wifi")        -> the live WifiServiceImpl  -> hook its javaClass
ServiceManager.getService("wifiscanner") -> WifiScanningServiceImpl   -> hook startScan*
ServiceManager.getService("MiuiWifiService")
```

and applies one policy to every non-platform caller (`Binder.getCallingUid()`; uid 0/1000 always see
real data, per the safety invariant that protects `WifiCloudSync`):

* `wifiSimulation = true` → the synthetic AP list (previous behaviour);
* `wifiSimulation = false` while a simulation runs → **empty list** (`wifi.withheld … uid <caller>`),
  i.e. exactly what the platform returns with the radio off;
* the async path (`WifiScanner.startScan*`) is refused outright, so a listener-based scan cannot
  bypass the getter.

`hook/…/framework/GnssHooks.kt` — real GNSS truth is withheld from app listeners while simulating:
`IGnssNmeaListener`, `IGnssStatusListener`, `IGnssMeasurementsListener`, `IGnssNavigationMessageListener`
deliveries are cancelled (`gnss: real onNmeaReceived withheld …`), because a locator SDK that parses
NMEA computes a position of its own and never touches `LocationManagerService`. `gpsSignalSimulation =
true` still works: the synthetic data is pushed straight into the listener objects, bypassing that gate.

### 5.2 Client layer (fallback, documented as such)

`hook/…/client/WifiClientHooks.kt` — while a simulation is active in a spoofed process, the real
WiFi identity is never handed out:

* `wifiSimulation = true` → unchanged behaviour: `getScanResults()` returns the synthetic AP list.
* `wifiSimulation = false` **and** a position is active → `getScanResults()` returns an **empty
  list** (what the platform itself returns with WiFi off, so every locator already handles it) and
  `getConfiguredNetworks()` returns an empty list.
* `WifiInfo.getBSSID()/getMacAddress()` → `02:00:00:00:00:00`, `getSSID()` → `<unknown ssid>`,
  `getWifiSsid()` → `null`. These are exactly the platform's own "unavailable" answers, so no app
  can tell the difference from a WiFi-off device.
* When the engine is stopped (`ClientMirror.hasPosition() == false` + `ClientMirror.stateKnown`) the
  module stays completely inert and real values flow through.
* Independent mode is respected: `Scope.clientSpoofingAllowed()` gates everything, so a
  non-whitelisted scoped app keeps the real values (that is the semantic of 独立模拟).

Acceptance evidence is one grep — every suppression logs once:

```
[ImmoralLocation] wifi identity redacted for com.autonavi.minimap (scanResults=0, wifiSim=false)
[ImmoralLocation] wifi identity redacted for com.autonavi.minimap:locationservice (getBSSID)
```

Nothing outside the client layer changed: no framework hook, no engine change, no app-side change,
and the `wifiSimulation = true` path is byte-for-byte the previous code path.

### 5.1 Why an empty list instead of synthetic APs

The design question is whether `wifiSimulation = false` + an active simulation should look like
"WiFi is off" (empty) or like "WiFi is on and near the simulated point" (synthetic APs derived from
the position, i.e. making `wifiSimulation` implicit).

**Decision: empty.** Reasoning:

1. **The synthetic list does not actually help a locator.** Unicast BSSIDs synthesized from a
   coordinate are not in any provider's database, so the cloud answers *"cannot locate"* — the same
   outcome as an empty scan, but only after uploading a fabricated fingerprint. There is nothing to
   gain and one more inconsistent signal to explain.
2. **Empty is a state every app already handles.** It is exactly what `getScanResults()` returns with
   the WiFi radio off, with scanning throttled, or before the first scan — a code path every locator
   SDK has been shipped against for a decade. Our A/B test proves 高德 behaves correctly in that state
   (it falls back to `LocationManager`, which we own).
3. **Fabrication must stay opt-in and complete.** `wifiSimulation = true` means "build a whole
   synthetic wireless world" (AP list consistent with the position, and the `immoral.wifiSim` flag on
   the state bus so `WifiInfo`/scan results agree). Keeping that behind an explicit toggle preserves
   the documented meaning of the toggle; making it implicit would silently change every existing
   configuration and would make `wifiSimulation` a no-op.
4. **Honest failure mode.** The observable difference is only "scan list is empty while the radio
   reports on" — which happens on real devices all the time (scan throttling). A fake AP list, by
   contrast, is observable as *data that cannot exist* (BSSIDs nobody has ever seen).

A dedicated WiFi analyser app that is LSPosed-scoped to this module would show no networks while a
simulation runs. That is the accepted cost; such an app is not a location consumer, and it keeps the
real values as soon as the engine is stopped.

### 5.2 Blast radius (one-line summary for the README)

> While a simulation is active in a **spoofed** process (`independentMode = off`, or the package is on
> the independent-mode whitelist), the process no longer receives the device's **real wireless
> identity**: `WifiManager.getScanResults()` and `getConfiguredNetworks()` return empty, and
> `WifiInfo.getBSSID()/getMacAddress()/getSSID()/getWifiSsid()` report the platform's own "unknown"
> values. With `wifiSimulation = true` the synthetic AP list is returned unchanged. When the engine is
> stopped, or for a non-whitelisted package in independent mode, nothing is intercepted and the app
> sees the real values exactly as before.


### 5.3 Product decision: "unknown ⇒ withhold" (invariant 5b)

**Decision: a scoped process must never be handed the device's real wireless identity while a
simulation is running *or while the client layer cannot tell whether one is running*.**

`ClientMirror.stateKnown` is the third state that makes this implementable: it becomes `true` only
when a state-bus read *definitively* answered — either the framework's spoofed last-known (extras
`immoral.*` present ⇒ ACTIVE) or a non-null last-known without those extras (⇒ the framework is not
publishing ⇒ STOPPED). Until then the client layer **fails closed**:

| surface (in a scoped process) | simulation running | state **unknown** (cold process) | engine known stopped |
|---|---|---|---|
| `WifiManager.getScanResults` | synthetic APs (`wifiSimulation`) else empty | **empty** | real |
| `WifiManager.getConfiguredNetworks` | empty | **empty** | real |
| `WifiManager.startScan / startScanActive / WifiScanner.startScan` | passthrough | **refused (`false`/`null`)** | passthrough |
| `WifiInfo.getBSSID/getSSID/getMacAddress/getWifiSsid` | "unknown" values | **"unknown" values** | real |
| `LocationManager.getLastKnownLocation` | synthetic fix | **`null`** | real |
| `TelephonyManager.getAllCellInfo / getCellLocation` | synthetic (or empty) | **empty / `null`** | real |

Cost, stated plainly: a Wi-Fi analyser (or a "connect to my network" flow) that is LSPosed-scoped to
this module sees **no networks** while a simulation runs, and for the brief window at process start
before the state is known. It gets the real values back the moment the engine is stopped and the
mirror has confirmed it. That is the accepted price for the guarantee the product actually sells:
a scoped app must not be able to determine the real position by any side channel.

*Why this is not paranoia:* the first call a locator SDK makes in a fresh process happens **before**
the client mirror can read the state bus, and the answer to that very first call is the one the SDK
keeps. Measured on 高德地图: the injected fix was visible at t+4 s and the real city was already drawn
at t+12 s, while the first redacted `getScanResults` was logged at t+44 s — 32 s too late.

**Implementation trap that fail-closed creates (and how it is handled).** The mirror's own state-bus
read calls `LocationManager.getLastKnownLocation()` — the very method the client hook now answers with
`null` while the state is unknown. Without a guard that is a deadlock: the state can never become
known, so the client layer stays fail-closed forever and the app loses last-known, cells and WiFi at
once. `ClientMirror.bypass` (a `ThreadLocal` set around its own pull) is therefore checked at the top
of the client location hook: the mirror's read passes straight through to the **framework** hook,
which does know the engine state and returns the spoofed fix with the `immoral.*` extras. Any future
client-side hook that the mirror uses to learn state must check `bypass` the same way.

## 6. Regression evidence

`:core:test` — **BUILD SUCCESSFUL** (228 tests) on every build of this change.
`:app:assembleDebug` — **BUILD SUCCESSFUL**; APKs built and installed: `742F41A1…` (client
fail-closed) and `244C8601…` (framework GNSS/WiFi suppression, the current device build).

**高德地图, framework scope only (`system` in LSPosed, 高德 itself NOT scoped)** —
`hook/harness/amap_verify.ps1 -Seconds 180`, frames in `amap-evidence/verify/`:

| t | frame | map centre |
|---|---|---|
| +4 s | `amap_t  4.png` | 天安门, Beijing (simulated ✅) |
| +8 s | `amap_t  8.png` | 天安门 ✅ |
| +12 s | `amap_t 12.png` | **杭州 建国北路** (real ❌) |
| +16 … +180 s | `amap_t 16/20/30/45/60/90/120/150/180.png` | 杭州 (real ❌) |

All frames `foregroundOk=true`, no system dialog, map interactive (the harness now asserts
`topResumedActivity` before every frame and aborts otherwise). Framework evidence in the same run
(`logs/amap_verify_hook.log`): `gnss: real onNmeaReceived withheld from a listener`,
`gnss: real onSvStatusChanged withheld …`, `gnss: real onGnssMeasurementsReceived withheld …` once per
second for the whole window, while `dumpsys location` shows **only** our synthetic fix
(`Location[gps 39.908700,116.397500 …]`, 299 gps + 1216 passive deliveries to 高德 in 5 minutes).

So: the interception is complete and provably firing, and the app still ends up on the real city.

## 7. How to reproduce the A/B in two commands

```powershell
# simulated position is active (Beijing), 高德 in the foreground
adb shell am force-stop com.autonavi.minimap; adb shell monkey -p com.autonavi.minimap -c android.intent.category.LAUNCHER 1
# A: WiFi ON  -> 高德 jumps to the real position within ~15 s
# B: WiFi OFF -> 高德 stays on the simulated position
adb shell svc wifi disable   # or: enable
```

`e2e/run_matrix.ps1 -Only 16 -AmapSeconds 180` automates the same measurement numerically (fake
position vs device real location, 800 m tolerance, pans the map every 30 s to force a re-locate).

## 8. Experiment matrix (what each hypothesis predicted, and what happened)

Every row is a device run on this phone, engine STATIC, simulated point = 天安门 39.9087,116.3975,
real device position = 杭州 建国北路 (≈1100 km away). "Revert" = the map drew 杭州 at street level.

| # | Interception in place | AMap's own network | Revert? | Conclusion |
|---|---|---|---|---|
| 1 | none (module not even loaded) | up | yes, t≈12 s | baseline |
| 2 | none, WiFi radio **off** | down (wifi off) | no (45 s) | the leak is *network-dependent* |
| 3 | client-layer: real scans→empty, WifiInfo→unknown, startScan→false (fail-closed) | up | yes, t≈12 s | the redaction fired only from t+44 s in that build; and later runs with it live still reverted |
| 4 | **framework**: all real GNSS truth withheld (NMEA, SvStatus, measurements) + framework scan results withheld + LMS fixes replaced | up | yes, t≈12 s | GNSS truth is not the channel either |
| 5 | as #4, AMap uid 10264's traffic `DROP`ped via iptables | **down** | yes, t≈32 s | not AMap's own sockets |
| 6 | as #4, `pm clear com.autonavi.minimap` (no stored state at all) | up | yes, ~t+30…60 s | not (only) a stale on-disk cache either |
| 7 | as #4, both radios off (`wifi`+`data`) | down | no (35 s, app partly on splash) | needs the radio |

Framework facts measured during #4 (`dumpsys location`, `logs/amap_verify_hook.log`):
* every fix the framework handed 高德 was ours — `Location[gps 39.908700,116.397500 …]`, 299 gps and
  1216 passive deliveries in 5 minutes, all four providers' `last location` identical;
* `gnss: real onNmeaReceived / onSvStatusChanged / onGnssMeasurementsReceived withheld from a listener`
  fired once per second for the whole window;
* the app is 杭州 by t+12 s regardless.

## 9. Where the remaining leak is (open) and what is *not* the cause

Ruled out by the matrix above: stale `lastKnownLocation`, a missed `requestLocationUpdates`
registration, a dropped listener, independent-mode filtering, the ticker, the framework report path
(#4 shows only our fixes reaching the app), real NMEA/GNSS truth (#4), the app's own BSSID list (#3),
the app's own sockets (#5).

What still fits every observation:

* a **location source that is not `LocationManagerService` and not the app's own socket** — i.e. a
  vendor/system service on this HyperOS build that 高德 binds to and that performs the fingerprint
  lookup with *its own* uid and network access. #5 (AMap's uid blocked) does not touch such a
  service's traffic, and #7 (radio off) does break it because it needs the radio. We did not find a
  bound service in `dumpsys activity services` at the moment of the check, so this is a hypothesis,
  not a proven fact; the next experiment would be to enumerate the app's binder clients while it is
  locating (`dumpsys activity services <pkg>`, `dumpsys activity provider`, `service list`) and to
  correlate with the t+8…12 s window.

For the product this means, stated plainly: **the framework layer can stop every channel we can name**
and it now does (that is a real hardening win — 百度地图 and every legacy locator get a consistent,
fail-closed view), **but it cannot yet stop 高德's own resolved position on this device.** The
`LocationSpoofer`-style project the user referenced gets the last mile by hooking *inside* the app,
which the product explicitly rules out ("只勾 framework，不侵入目标 app"). Anything framework-only has
to win inside 高德's own decision logic, and we have not found a lever there yet.

Practical mitigations that are true today (not fixes, and they must be described as such):
* the map shows the simulated position for the first ~10 s of every cold start (so a user who looks
  right after switching apps sees the right place);
* with a system-wide simulation and 高德 freshly installed (state cleared), the simulated point holds
  until 高德's own first locate completes;
* `wifiSimulation=true` / `gpsSignalSimulation=true` change nothing about this behaviour (measured),
  so they should not be presented as a workaround.

## 10. FINAL RESULT — the framework wifi hook was the missing piece

Build `135A7C4C06D2D92FF877FC2CDE5A0EDECB7DFC4FE3E64CE5CC0E861974C22BDD` (17:07), LSPosed scope =
**`system` only** (`activate_module.ps1 -Packages android -Prune`; 高德/百度/云运动 *not* scoped),
engine STATIC, all simulation toggles off, simulated point 天安门 39.9087,116.3975, real position 杭州.

**The seam is finally live** (`/data/adb/lspd/log/modules_*.log`, boot 17:26):

```
17:26:34 hook MISS com.android.server.wifi.WifiServiceImpl (class not found) — will hook the service instance instead
17:26:34 service 'wifi' not registered yet (hook will install when it appears)
17:26:46 service 'wifi' registered at +11s via getService -> com.android.server.wifi.WifiServiceImpl — hooking it now
17:26:46 service 'wifi' hooked: 1 scan-result method(s) on com.android.server.wifi.WifiServiceImpl
17:26:46 service 'wifiscanner' registered at +11s via getService -> com.android.server.wifi.scanner.WifiScanningServiceImpl — hooking it now
17:26:46 service 'wifiscanner' hooked: 1 scan-result method(s) on com.android.server.wifi.scanner.WifiScanningServiceImpl
```

**高德地图 now holds the simulated position for the whole window** — two independent cold starts:

| run | frames | verdict |
|---|---|---|
| A (`hook/harness/amap_verify.ps1 -Seconds 180`, 17:36–17:40) | `amap_t4 … amap_t180` | 天安门/故宫/午门/西池子大街/王府井 at **t+4 … t+180**, `foregroundOk=true` every frame |
| B (cold start 17:43, 100 s) | `amap-evidence/90_confirm_t20/t60/t100.png` | 天安门 at t+20, t+60 and **t+100** |

Before this build the identical harness, identical engine state and identical scope reverted to 杭州 at
t+12 in three separate runs (§6, §8).

**Honest caveat on the mechanism.** The per-call proof line I designed for this
(`wifi.withheld … uid 10264`) **did not appear** in either run: `grep -c wifi.withheld` = 0. So the
service instance is hooked (proven above) but 高德 did not exercise `getScanResults` in these runs —
the behaviour change is therefore empirical, not yet attributed line-by-line. Two candidate
explanations, both worth one more session:
1. 高德 obtains the scan list through `WifiScanningServiceImpl` (also hooked) or another of the
   service methods, so the *refusal* path fired without the log line I grep for; or
2. it never asks for the fingerprint any more because the GNSS-truth suppression from §5.1 removed its
   other hint, and the two changes together are what flipped the outcome.
The evidence published here is: **framework-only scope, 3–4 minutes of 高德, simulated position, no
client layer involved.** What is *not* claimed: that a single named code path is proven responsible.

### 10.1 Confound ruled out: a third-party injector running does not change the outcome

`com.lerist.fakelocation` (the reference product, installed on this test device, reported by QA to have
been "leaving a Hangzhou mock") was force-stopped for runs A and B, so it had to be excluded as the
reason they passed. Run C started it on purpose and repeated the cold start:

| run | lerist running | verdict |
|---|---|---|
| C (17:48, 65 s) | **yes** (`com.lerist.fakelocation` pid 24566) | 高德 on **天安门** at t+25 and t+65 — simulated (`amap-evidence/91_withlerist_t25/t65.png`) |

So the result is not an artefact of which injector happened to be alive: with the framework wifi seam
live (§10), 高德 keeps the simulated position, with or without a second spoofing app in the background.

### 10.2 The decisive test: 高德 must not be able to "remember" the real position

Runs A–C had one weakness: all of them started from an app whose stored position had recently been
cleared (`pm clear` at 16:27), so "it stopped reverting" could in principle have been a cache effect
rather than a fix. This run removes that objection, on the same build (`135A7C4C…`, scope `system`
only):

1. **Engine stopped** → cold-start 高德 for 45 s: it locates the **real** position on its own,
   杭州 建国北路 / 环城北路 / 杭州体育馆
   ([94_real_learned.png](amap-evidence/94_real_learned.png), 18:10).
2. **Engine started** at 天安门 → force-stop → cold start again:
   - t+30 s and **t+75 s** — 高德 on **天安门 / 午门 / 南池子大街 / 皇史宬 / 西长安街 / 北京饭店**,
     user marker on 天安门 ([94_after_t30.png](amap-evidence/94_after_t30.png),
     [94_after_t75.png](amap-evidence/94_after_t75.png), 18:11).

So the app had *just* stored the real position and still follows the simulation afterwards. The
result is not a stale-cache artefact.

### 10.3 What is still not attributed (be precise)

`wifi.withheld … uid 10264` and `wifi.replaced … uid 10264` were **both** checked, including a run
with `wifiSimulation = true` (synthetic APs): **neither ever fires for 高德**
(`93_wifisim_t35.png`). The framework wifi seam is therefore *installed but never exercised by this
app* — so the wifi suppression is **not** what fixes 高德, and §10 must not claim it is.

The change that does correlate with the fix is the **GNSS-truth suppression** of §5.1
(`gnss: real onNmeaReceived / onSvStatusChanged / onGnssMeasurementsReceived withheld`, logged once per
second during every run), which landed together with the client-layer fail-closed work between build
`244C8601…` (still reverted at t+12 s) and `135A7C4C…` (holds). That correlation is one experiment
short of proof — the clean test would be a build with the GNSS delivery gate disabled and everything
else identical, run against the same "高德 just learned the real position" starting state as §10.2.
Until that exists, the honest claim is:

> **With the framework GNSS-delivery gate active, 高德地图 holds the simulated position for at least
> 180 s on this device, framework scope only, including right after it has located the real position.**
> The named code path most likely responsible is the GNSS-delivery gate; it is not yet isolated by a
> control build.

### 10.4 Control experiment: the GNSS truth gate is **not** the cause (inconclusive, both arms held)

Build `BF11CF6644A25C4387BB30713A5A4678696DFC48D12B53C50E4D51DF6C54BA2B` (18:19) adds **one runtime
switch** to the GNSS delivery gate — `persist.immoral.gnss_truth_gate` (default on) — so both arms can
run on the **same boot, same APK, same scope** (`system` only). Protocol per arm: engine **off** →
cold-start 高德 40 s and let it locate the real position → engine **on** at 天安门 → force-stop →
cold start → frames at t+12/30/60/90/120.

| arm | gate | "learn" frame | t+12 … t+120 |
|---|---|---|---|
| A | `persist.immoral.gnss_truth_gate=0` (**off**) | `95_ctrl_gate_off_learn.png` — 杭州 建国北路/环城北路 (real, as intended) | **北京 天安门** at t+12/30/60/90/120 (`95_ctrl_gate_off_t*.png`) |
| B | `persist.immoral.gnss_truth_gate=1` (**on**) | `96_ctrl_gate_on_learn.png` | **北京 天安门** (`96_ctrl_gate_on_t*.png`) |

**Result: the control held too, so the GNSS truth gate is *not* what makes 高德 stay simulated.**
And the arm is weaker than intended, which must be stated: in this boot the gate never actually fired —
`grep -c "gnss: real"` over the whole boot log is **0** in *both* arms, i.e. no real GNSS deliveries
reached an app listener at all (the previous boot logged them once per second). So arms A and B were
**effectively identical** and the comparison is inconclusive rather than a clean refutation. What it
does establish is the important negative: **the fix does not depend on the GNSS delivery gate being
active**, so §10.3's "most likely the GNSS gate" is now withdrawn.

### 10.5 Where the conclusion stands (final wording for the acceptance doc)

What is *measured*, repeatedly, on framework-only scope with the engine running:
* before the 16:27 `pm clear` + the 17:26 build: 高德 reverted to the real city at ≈t+12 s in four runs;
* afterwards: 高德 held the simulated position in **six** independent runs, including two where it had
  *just* located the real position minutes earlier (`94_real_learned` → `94_after_t75`,
  `95_ctrl_gate_off_learn` → `95_ctrl_gate_off_t120`) and one with a second root injector alive
  (`91_withlerist_t65`);
* with the GNSS gate runtime-disabled the app still held (`95_ctrl_gate_off_t*`).

What is *not* established: which change is responsible. The candidates in this branch are the
client-layer fail-closed work, the GNSS delivery gate, the framework wifi seam (installed but never
exercised — see §10.3) and 高德's own state after the `pm clear`. The one experiment that would settle
it is a build **without** any of my hook changes (the pre-change APK) run against the *same* post-clear
app state; that is a 10-minute run and it is the honest next step, not something this document claims.

**Product statement that is safe to publish:** *with the current module (framework scope only, engine
running), 高德地图 keeps showing the simulated position for at least three minutes, including right
after it has located the real position; the four earlier failures are recorded above and the module
change that flipped the behaviour is not isolated yet.*

## 11. 对照实验：不含本轮 hook 改动的 pre-fix 构建（2026-11-01 18:42，Lead 执行）

前面的结论是"行为已修复、成因未隔离"。为了隔离，用 `git worktree` 在提交 **`490ea3b`** 上构建了一个
**不含本轮任何 hook 改动**的 APK（该提交没有 GNSS 投递闸门、没有 wifi 实例 hook、没有 client 层 fail-closed）：

```
git worktree add ..\pre-fix 490ea3b
cd ..\pre-fix && gradlew.bat --offline :app:assembleDebug     # app-debug.apk, sha256 E5068DF2…
```

**协议与"修复后"各臂完全一致**（LSPosed scope 仅 `system`；先关引擎、冷启动高德 45 s 让它自己定位到真实杭州；
再开引擎到天安门、force-stop、冷启动；采样 t+12/30/60/90/120）。

| 构建 | 起跑状态 | 结果 |
|---|---|---|
| **pre-fix `490ea3b`**（无本轮 hook 改动） | 高德已自行定位到真实杭州 | **t+12 / t+30 / t+60 / t+90 / t+120 全部保持天安门**（`docs/amap-evidence/97_prefix/97_prefix_t*.png`，用户标记在天安门） |
| 当前构建 `135A7C4C…` | 同上 | 全部保持天安门（§10） |

**结论（必须写进验收表，不能含糊）**：

1. **那个"t+12 s 回杭州"的现象，用当前构建和 pre-fix 构建都复现不出来。** 所以它不是"framework 层缺一条 hook"
   这么简单，也不是我们后来加的 wifi 实例 hook 修好的——两条路径（有/无）今天都保持模拟位置。
2. 14:19–16:20 之间的四次回落，发生在**当时的中间态工作树构建**上；其中最可疑的是当时在工作树里、但 `490ea3b`
   里没有的 **client 层 fail-closed** 改动（在状态未知时把 `getLastKnownLocation` 返回 `null`、扫描结果返回空）。
   对一个自己不定位、只吃系统回调的 app，这种"突然什么都不给"的变化是有可能把它推向自己的兜底定位的。
   **这是一个假设，不是结论**——要证明它需要单独构建"只有 client fail-closed"的版本。
3. 产品决策（本轮已执行）：**默认作用域保持 framework-only**（`system` + 我们的 app + verifier），
   client 层只作为 ROM 差异兜底、且**不再默认启用**；wifi 实例 hook 作为加固保留，但**不再声称是修复**。
4. 公开措辞：*当前模块在纯框架作用域下，高德地图会稳定保持在模拟位置（≥3 分钟，含"它刚定位到真实位置之后"），
   这一行为在 pre-fix 构建上同样成立；因此我们记录的是"现象消失"，而不是"某条代码路径修好了它"。*

## 12. 下一轮该做什么

1. 用"只有 client fail-closed / 只有 wifi 实例 hook / 两者都有"三个单变量构建，把 §11 的假设证伪或坐实；
2. 把 `wifi.withheld`、`wifi.replaced`、`gnss: real … withheld` 三条机制行做成一次 run 的断言（现在全是人工 grep）；
3. 若最终确认是 client fail-closed 引起，删掉那条策略并把它写进 hook/README 的"不要这样做"清单。

## 11. Operational consequences of the GNSS truth gate (do not read them as regressions)

While a simulation runs, the framework cancels real GNSS deliveries to app listeners
(`§5.1`). Synthetic data still flows when `gpsSignalSimulation = true`; the gate bypasses our own
pushes on purpose. The visible effects, so nobody has to rediscover them:

| configuration | what an app sees | note |
|---|---|---|
| `gpsSignalSimulation = true` | our synthesized satellites / NMEA | unchanged from before |
| `gpsSignalSimulation = false` | **no GNSS status / NMEA / measurements at all** | deliberate (fail-closed): a locator must not be able to compute the real position from raw chip data |

Consequence for the acceptance harness: `9-gnss` samples zero with the toggle off — that is the new
policy, not a broken stream, and `8-steps` reads zero under `system`-only LSPosed scope because the
client-layer sensor hooks are not installed in the app process in that configuration. Similarly
`6-cells`, `7-wifi` and `10-independent` read differently under framework-only scope than under a full
scope, because the client layer is what used to enforce them inside the app process:
* `10-independent` is the one to treat as a genuine open bug — with the client layer out of scope the
  **framework** per-registration filter has to carry 独立模拟 alone, and a non-whitelisted app seeing
  the fake fix means that filter has a hole the client layer was masking.

And the loose end from §10.3, in one line: the framework wifi seam is installed
(`service 'wifi' registered at +11s … hooked: 1 scan-result method(s)`) but **never exercised** —
`wifi.withheld` / `wifi.replaced` never fire, for 高德 or for the verifier — so real scan lists reach
apps through a path that is not the method we hooked. A single `grep wifi.withheld` during any run
settles it.

## 12. The wifi loose end, resolved: wrong method *shape*, on a vendor subclass

QA's 19:49 build printed it plainly (`/data/adb/lspd/log/verbose_*.log`, scope `system +
com.android.phone`, `wifiSimulation=true`):

```
hook MISS wifi#getScanResults*            (no matching method on com.android.server.wifi.WifiServiceImpl)
hook MISS wifiscanner#getScanResults*     (no matching method on com.android.server.wifi.WifiScanningServiceImpl)
hook MISS MiuiWifiService#getScanResults* (no matching method on com.android.server.wifi.MiuiWifiService)
```

So §10.3's "installed but never exercised" is fully explained, and it is **not** a scope or a
class-loader problem any more:

1. HyperOS **subclasses** the AOSP service (`MiuiWifiService`), so the instance we hook is a vendor
   class, not `WifiServiceImpl`.
2. More importantly, the matcher was wrong on **shape**: it required a `java.util.List` return, but
   modern AIDL hands scan results back as a **`ParceledListSlice`**, which is *not* a `List`. A method
   can therefore exist and be hooked-never-matched at the same time — exactly the "hook OK, 0 methods,
   no `wifi.withheld`" signature we chased for hours.

Fix applied (`hook/…/framework/WifiHooks.kt`, uncommitted at time of writing):

* match by name (`scanresult` / `scanlist`, any case) **and** by either return shape
  (`java.util.List` **or** a `*ParceledListSlice` / `*Slice`);
* synthesise the answer in the method's own return shape (`asReturnShape`): a plain list stays a list,
  a slice is rebuilt via `ParceledListSlice(List)`; if the container cannot be built the call is left
  untouched and `wifi.slice.unavailable` is logged once — failing open with a log is preferable to
  throwing inside `system_server`;
* each hooked method now logs its full return type (`wifi#getScanResults -> ParceledListSlice`), and
  when nothing matches the inventory lists every `scan*` method **with** its return type, so the next
  run names the real API instead of us guessing.

This is a fix to the *mechanism*, not yet a verified 高德 improvement: per §10.3 the wifi channel was
never shown to be what 高德 uses, so §10.5's claim about the product stays unchanged. What changes is
that the framework wifi seam can now actually fire, and `wifi.withheld … uid <caller>` is the one grep
that proves it.

### 12.1 What this does *not* fix (QA's remaining framework-synthesis gaps)

* `framework/telephony: 0 hook targets resolved` in `system_server` (`PhoneInterfaceManager` absent,
  `TelephonyRegistry#getAllCellInfo` renamed) ⇒ real cells still reach apps; `6-cells` correctly reads
  "unchanged". **The same shape-aware fix has since been applied to `TelephonyHooks` (§13) — awaiting a
  device run.**
* Independent-mode delivery filter logs `pkg=UNRESOLVED (independent)` and lets deliveries through to
  `com.xiaomi.aicr`, `com.google.android.gms`, `com.xiaomi.metoknlp`, `com.xiaomi.location.fused` —
  the hole the deleted client layer used to mask. **This is the highest-value follow-up.**
* `status file write DENIED (…/files/fw_status.json: EACCES)`: the framework cannot write into the
  app's private dir, so any UI oracle based on that file is dead by design.

## 13. Device verification of the wifi seam, and the same fix applied to telephony

QA ran `7-wifi` on the build carrying the §12 fix (installed 21:39, scope
`android,com.android.phone`) and it **PASSes** — the seam is no longer "installed but never
exercised":

```
hook  : wifi#getScanResults -> 5 synthetic APs for uid 10274
check : 7-wifi PASS   A: 5 APs (first BSSID ca:a1:41:0e:7d:66); C: 6 APs; changed=True
```

What that proves, precisely: the method resolves and hooks, the reply is rebuilt **in the method's own
return shape**, the override is **per-uid** (`uid 10274` = the verifier, so `Binder.getCallingUid()`
does its job), and the list **follows the simulated position** (5 APs at A → 6 at C, `changed=True`).

**Correction to my own acceptance criterion** (kept because I nearly left a phantom blocker): with
`wifiSimulation = true` the code takes the *replace* branch, so `wifi.withheld … uid …` is **absent by
design** — §12's last sentence was wrong about that. The witness for "the seam works" is
`-> N synthetic APs for uid X` plus "the list changes with position". `wifi.withheld` belongs to
`wifiSimulation = false`, which is a different assertion (real BSSID list suppressed) and needs its
own run.

**Telephony: same bug, same fix** (`hook/…/framework/TelephonyHooks.kt`, uncommitted at time of
writing). QA's boot log showed the identical signature — `0 hook targets resolved, 2 missed` in
`system_server` (`PhoneInterfaceManager` absent, `TelephonyRegistry#getAllCellInfo` "no matching
method") and `1 resolved, 1 missed` inside `com.android.phone`. The hook now:

* matches by name `*cell*` **and** by either return shape (`List`, `*ParceledListSlice`, `*Slice`) —
  `getAllCellInfo` is simply not the name that exists on this ROM, and a `ParceledListSlice` return
  would have been invisible to the old List-only matcher even if the name had matched;
* replies in the method's own shape (`asReturnShape`), leaving the call untouched with a one-shot
  `cell.slice.unavailable` if the container cannot be built;
* logs each hooked method with its return type, and when nothing matches dumps every `cell*` method
  **with** its return type, so the next run names the real API instead of us guessing.

Acceptance for telephony is two lines on the next run: `hook OK …#<name> -> <ReturnType>` and
`cell.replaced … -> N synthetic cells`. Until that exists, `6-cells` stays an honest FAIL.

### 13.1 Telephony: measured answers (and a second, harder gap)

Run on the build with the shape-aware matcher, scope `system` + `com.android.phone`, boot
`22:26` — every line below is verbatim from `/data/adb/lspd/log/modules_*.log`.

**1. In `system_server` there is nothing to hook, and this is now proven rather than suspected.**
`TelephonyRegistry` has no cell *getter* at all; the inventory dumped every `cell*` member:

```
hook MISS com.android.server.TelephonyRegistry#*cell* (no matching method; cell* candidates:
  -$$Nest$fgetmCellIdentity:CellIdentity[], -$$Nest$mnotifyCellLocationForSubscriber:void,
  notifyCellInfo:void, notifyCellInfoForSubscriber:void, notifyCellLocationForSubscriber:void,
  notifySimultaneousCellularCallingSubscriptionsChanged:void)
==== framework/telephony: 0 hook targets resolved, 2 missed ====
```

They are all *notifiers* (void). `CellIdentity[]` is not returned to anyone. So the framework-side
cell seam cannot live in `system_server` on this ROM — the earlier "0 of 2" was never a matcher bug.

**2. The real getters are in the `com.android.phone` process, and the shape-aware matcher finds them.**
(`PhoneInterfaceManager`, pid 4943, uid 1001):

```
hook OK   com.android.phone.PhoneInterfaceManager#getAllCellInfo -> java.util.List
hook OK   com.android.phone.PhoneInterfaceManager#getCachedCellInfo -> java.util.List
hook OK   com.android.phone.PhoneInterfaceManager#getNeighboringCellInfo -> java.util.List
==== framework/telephony: 3 hook targets resolved, 1 missed ====
```

All three are plain `java.util.List` on this ROM — so the name, not the shape, was the original
problem, and the *class* was the second problem. Note `getCellBroadcastIdRanges` was matched by the
first loose version of the matcher; it returns broadcast-id ranges and must never be replaced with
`CellInfo`s, so `isCellInfoGetter()` now excludes `*broadcast*` and `*simultaneous*`. That false
positive existed for one build and is gone.

**3. The remaining, harder gap: those hooks are installed but inert.** With `cellSimulation = true`,
`Start` and 高德 in the foreground for 25 s, **no `cell.replaced` line appeared**. The reason is
structural and worth stating plainly: `EngineRuntime` lives in `system_server`, and the phone process
has no engine at all, so `EngineRuntime.activeSnap()` is always `null` there. A hook in that process
therefore cannot synthesise anything until it is given a **state channel**.

Options for whoever picks this up, cheapest first:
* publish the simulated cells into `Settings.Global` the way `immoral_policy` already is
  (`system_server` can write it, any process can read it) and read them in the phone process;
* or resurrect a minimal `ClientMirror`-style bus reader (the deleted client layer had exactly this)
  and derive cells from the spoofed last-known `Location` + `SimulationEngine` (deterministic from
  position, see `docs/CONTRACTS.md` §3).

Until one of those exists, `6-cells` is a **named structural gap** — not a matcher bug, not a scope
problem, and not something more log-grepping will reveal.


