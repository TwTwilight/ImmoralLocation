# Automation & acceptance harness (`e2e/`)

This document explains how the on-device verification pipeline works and how to extend it.
Product/interface contracts live in [CONTRACTS.md](CONTRACTS.md) — read that first.

## Why it looks like this

Everything the product does happens *inside other processes* (`system_server`, scoped apps).
A unit test cannot prove that a navigation app now sees Shanghai, so the harness is built around
one idea: **let a tiny probe app print what the device actually reports, then assert on that
stream from the host.**

```
host (PowerShell)                    device
─────────────────                    ──────
e2e/activate_module.ps1 ───────►  LSPosed db (/data/adb/lspd/config/modules_config.db)
                                         │ scope rows → system_server + scoped apps
e2e/lib/bridge.ps1 ──── command ────►  engine (hook side)
                                         │
verifier app ──── VERIFY lines ────►  logcat tag ImmoralVerifier
e2e/lib/verifier.ps1  ◄── adb logcat ──┘
e2e/lib/report.ps1  ──► e2e/out/report.json + PASS/FAIL table
```

## Scripts

| Script | Purpose |
|---|---|
| `activate_module.ps1` | Enables the module for a scope list by editing the LSPosed SQLite db offline; restarts `lspd`; reboots when the framework scope must go live; verifies by read-back. |
| `install.ps1` | Build → install app + verifier → activate → evidence. The "just give me a working device" entry point. |
| `run_matrix.ps1` | Runs the acceptance checks (`0` config hygiene, engine `1–11`, UI/rework `12`, `12b`, `13`, `14`, `14b`, `14c`, `15a–15c`, `16`, `17`, `18`, `20`, `21`, `22`, release artifacts `23`, `24`), prints the table, writes `out/report.json`, exits non-zero on failure. `-SkipUi` runs only the engine half. |
| `screenshot.ps1` | `adb exec-out screencap -p` → validated PNG in `docs/screenshots/<name>.png`; prints and returns the path. Headless, no GUI. |
| `ui_gesture.ps1` | The screen layer: `uiautomator dump` tree + bounds queries, taps by text/id/desc, long press, swipes, and a real joystick press (`input motionevent DOWN/MOVE/UP`). |
| `capture_screens.ps1` | Drives the review states and captures `new-main-map` / `-joystick` / `-tools-sheet` / `-route` / `-placecard` / `-tapchip` / `-search` / `-layers` / `-dark-mode` into `docs/screenshots/`. Unreachable states are reported MISSING with the screen tree. |
| `e2e/route_trace.ps1` | Drives a real three-stop plan (origin / waypoint / destination by coordinates) and produces the artifacts needed to audit the geometry: polyline screenshot, the persisted `immoral_settings.xml`, the VERIFY playback stream and a summary JSON. |
| `e2e/final_pass.ps1` | The whole reproducible pass in one command: rebuild → install → activate → hook probe → matrix (`-Scope ui/full/engine`) → screenshot set → summary JSON with `gitHead`/`apkSha256`. |
| `lib/checks_ui.ps1` | The UI/UX-rework checks (`0`, `12`–`22`). They need the app in the foreground, so they are separate from the engine-level checks 1–11. |
| `lib/checks_release.ps1` | Release-artifact checks that need no device: `23-module-integrity` (xposed_init + hook classes survive R8) and `24-apk-size` (size budget). Run by CI as well. |
| `lint.ps1` | Static checks for the harness itself: parse errors, calls to undefined helpers, repo paths that do not exist. Runs in CI, needs no device. |
| `tools/lsposed_db.py` | The only thing that can read/write LSPosed's database (the device has no `sqlite3`). Commands: `dump`, `apply`, `check`. |

## The three command channels

`lib/bridge.ps1` speaks all three and can degrade automatically (`-Channel auto`).

### (a) broadcast — the authoritative channel

```powershell
Send-SetPosition -Lat 31.2304 -Lon 121.4737
```

The helper is deliberately shell-free: it writes the JSON byte-exactly to `e2e/out/payload.json`,
`adb push`es it, and runs [e2e/tools/bcast.sh](../e2e/tools/bcast.sh) on the device:

```sh
am broadcast -a com.immoral.location.action.COMMAND \
    --es com.immoral.location.extra.PAYLOAD "$(cat /data/local/tmp/e2e_payload.json)"
```

Inline quoting (`--es … '{"cmd":…}'`) silently loses the double quotes on the way through
host-shell → adb → device-shell; the JSON then fails `Codec.decodeCommand` and is dropped without
any error. That single trap cost this project several hours — always go through the file.

Payloads are `:core` `Codec` JSON (`classDiscriminator = "cmd"`, defaults encoded). Measured facts:

* the receiver is registered **inside `system_server`** (`dumpsys activity broadcasts` →
  `com.immoral.location.action.COMMAND requiredPermission=com.immoral.location.permission.CONTROL`,
  owner `2292 system/1000`), so the sender needs root (`su -c`) and **must not** use `-p <app>`:
  package-scoped broadcasts are filtered away from the system uid and never arrive;
* the hook confirms receipt in logcat: `command received: payloadChars=… senderUid=…` followed by
  `command decoded: SetPosition` — that is the cheapest way to prove the channel;
* `Broadcast completed: result=0` alone does **not** prove the command did anything; assert the
  effect (the matrix does).

### (b) SharedPreferences mirror — NOT usable for the framework

The framework polls a mirrored prefs file, but LSPosed relocates app prefs to
`/data/misc/apexdata/<uuid>/prefs/<pkg>/…` with mode `0660`, which `system_server` cannot read.
The hook says so itself:

```
W [ImmoralLocation] prefs channel: file present but NOT readable from system_server
  (app prefs are 0660 in apexdata) — prefs commands unavailable here, broadcast channel is authoritative
```

The helper is kept because it is the only channel that works when the receiver is not registered
yet, and because app-ui's own `sim_*` writes use exactly this format. Format notes that cost time:

* SharedPreferences decides the type from the **element name** (`<string>`, `<int>`, `<boolean>`);
  `<string>` puts its payload in the **element text**, not in a `value` attribute;
* the file must be written **without a BOM** (Android's parser rejects it) and with the app's
  uid/gid and mode `660`;
* in PowerShell, `$xmlNode.Name` returns the `name` **attribute** (XML ETS adapter), not the
  element name — use `.LocalName`.

### (c) UI

`uiautomator dump` + `input tap` (`Get-UiDump`, `Invoke-UiTap`, `Test-UiText`). This is the only
honest way to test favourites (收藏/快速返回) and the floating overlay, and it is preferred for the
real third-party acceptance apps.

The rework round added a second, stricter layer in [e2e/ui_gesture.ps1](../e2e/ui_gesture.ps1)
used by checks 12–21:

| helper | what it gives you |
|---|---|
| `Get-UiXml` / `Get-UiNodes` | the accessibility tree as flat objects (text / id / desc / class / bounds / clickable…), retried because `uiautomator dump` fails while an animation is in flight |
| `Get-UiBounds -Text/-Id/-Desc [-Exact]` | **all** matching nodes, so geometry assertions are possible (bridge.ps1's `Get-UiNodeBounds` returns only the first match) |
| `Tap-ByText` / `Tap-ById` / `Tap-Ui` | tap by selector, with the on-screen tree in the error message when nothing matches |
| `Invoke-LongPress` | `input swipe x y x y <ms>` — a non-moving press, which Compose reads as a long press |
| `Swipe` / `Invoke-UiSwipeDirection` | pan/zoom gestures, including "swipe from the middle of this rect" |
| `Start-JoystickPress` / `Stop-JoystickPress` / `DragJoystick` | one long `adb shell input swipe … <ms>` launched in the background, so the caller can sample the engine **while the gesture is still running**. (Deliberately not `input motionevent DOWN/MOVE/UP`: separate processes ⇒ separate input streams ⇒ Compose sees a cancelled gesture and the app sends no joystick vector at all.) |
| `Test-RectOverlap` / `Get-RectOverlapArea` / `Format-Rect` | overlap assertions (chromepieces, snackbar) |
| `Get-ScreenSize` / `Get-DpToPx` | 1080x2400 @440 dpi → 393x873 dp on the test device |
| `Get-AppString` | resolves `R.string.<key>` from `app/src/main/res/values/*.xml`, so the matrix never hard-codes a Chinese literal |
| `Set-DarkMode` / `Get-DarkMode` | `cmd uimode night yes/no` for the dark-mode screenshot |

Two device facts this depends on:

* **Compose test tags must be exposed as resource-ids.** `uiautomator` only sees `Modifier.testTag("…")`
  when the app root sets `Modifier.semantics { testTagsAsResourceId = true }`. Without it the tag is
  invisible and a check can only fall back to text matching — so the checks report `SKIP` (never a
  false `PASS`) when a tag is missing.
* `input motionevent <DOWN|MOVE|UP> <x> <y>` is available on this ROM (Android 15, `input` help
  lists it). Each event is a separate process, but the touch stream stays continuous because they
  share the same pointer id.

## Before any number is trusted

Two failure modes destroyed measurements during this project, and both are now guarded:

* **a foreign spoofer in the foreground.** A second FakeLocation app (`com.lerist.fakelocation`) had
  been left running with a Hangzhou mock; the framework's last fix read Hangzhou while the harness
  injected Shanghai, so every distance assertion would have been nonsense. `run_matrix.ps1` now
  force-stops a denylist, records `topResumedActivity` in `report.json`, and (with check
  `0-config-hygiene`) aborts when the injected fix does not come back.
* **a mutated engine config.** The hook stamps every injected fix with `immoral.*` extras
  (`stepSim`, `wifiSim`, `cellSim`, `gpsSignalSim`, `independent`, `cadence`, `steps`), so the real
  configuration is readable from `dumpsys location` without guessing. Check `0-config-hygiene`
  pushes the canonical config, then fails (and aborts the run) if any toggle reads ON.

## The 高德 check has a different oracle — by design

Every other check reads the framework's / the verifier's copy of the truth. That is exactly the wrong
oracle for "高德 reverts to the real position 30 s later":

```
during the real revert:   高德 draws 杭州
                          dumpsys location still says Location[gps 39.908700,116.397500 …]
```

`dumpsys` shows *our own injection*, so a dumpsys-based assertion PASSES on a broken build. Check 16
therefore collects **visual** evidence — `hook/harness/amap_verify.ps1` cold-starts 高德, pans/zooms and
screenshots at t+4/12/20/30/45/60/90/120/150/180 s — and asserts only that the evidence set is
complete and usable (all marks present, no truncated PNGs, 高德 still foreground, redaction lines
counted). The framework assertion is kept but labelled "necessary, NOT sufficient". The verdict is
read off the frames and recorded in `e2e/out/amap-visual-verdict.json` + `docs/ACCEPTANCE.md`.

## Screenshots (headless)

```powershell
.\e2e\screenshot.ps1 map-peek                 # -> docs/screenshots/map-peek.png
$p = .\e2e\screenshot.ps1 -Name dark -Quiet   # scriptable: only the path comes back
```

It never needs a GUI, so a teammate who cannot touch the phone can ask for a picture and read the
PNG from the workspace. Capture path:

1. `adb exec-out screencap -p` written straight into a file through
   `Start-Process -RedirectStandardOutput` (an OS-level redirect — the PNG never crosses the
   PowerShell pipeline, where it would be re-encoded as text);
2. validation: PNG magic, an `IEND` chunk at the end, `>= 2 KB`;
3. fallback if (1) produced something invalid: `adb shell screencap -p /data/local/tmp/…` + `adb pull`.

A capture that fails both strategies throws, so a "screenshot" can never silently be a 0-byte file.
Width/height are parsed from `IHDR` and reported, which catches a half-drawn frame early.

## Reading the ground truth

`verifier` prints one JSON object per line on tag `ImmoralVerifier`:

```
VERIFY {"kind":"ready","package":"…","sdk":35,"autotest":true,…}
VERIFY {"kind":"clock","wallMs":…,"elapsedMs":…}
VERIFY {"kind":"providers","all":["passive","network","fused","gps"],"enabled":[…],"locationEnabled":true}
VERIFY {"kind":"providerEnabled","provider":"fused","enabled":true,"present":true}
VERIFY {"kind":"lastKnown","provider":"gps","present":true,"lat":31.230416,"lon":121.473701,…}
VERIFY {"kind":"location","source":"onLocationChanged","provider":"gps","lat":..,"lon":..,"mock":false,…}
VERIFY {"kind":"cell","mobile":"LTE","mcc":"460","mnc":"00","tac":22538,"ci":48356046,"pci":302,"dbm":-84}
VERIFY {"kind":"cellLocation","value":"…"}
VERIFY {"kind":"wifi","count":26,"enabled":true,"aps":[{"bssid":"…","ssid":"…","level":-48,"freq":2417}]}
VERIFY {"kind":"steps","hasCounter":true,"hasDetector":true,"counterValue":128.0}
VERIFY {"kind":"stepCounter","value":140.0,"elapsedMs":…}
VERIFY {"kind":"gnss","satellites":18,"usedInFix":12,"maxCn0":41.5,"sats":[…]}
VERIFY {"kind":"autotest","phase":"end","count":10}
```

`source: "onLocationChanged"` matters: it proves the value came through
`requestLocationUpdates`, i.e. the injection path, not a one-shot `getLastKnownLocation`.

Headless run (no screen interaction needed):

```bash
adb shell am start -n com.immoral.location.verifier/.VerifierActivity \
  --ez autotest true --ei count 10 --ei intervalMs 2000 --ez stream true --ez steps true
```

Useful helpers from `lib/verifier.ps1`: `Get-VerifyMark` (cursor), `Get-VerifySamples`,
`Get-LiveLocationSamples`, `Measure-Track`, haversine/bearing helpers mirroring `:core` `GeoMath`.

## Adding a check

Engine-level (no UI):

1. Append a block to `run_matrix.ps1` guarded by `Test-Selected '<id>'`.
2. Drive the engine through `lib/bridge.ps1`, never by poking internals directly.
3. Assert on VERIFY lines (or on `LSPosedFramework` log lines when you test hook installation).
4. Report with `Test-Result -Id … -Name … -Condition … -Metrics @{…}` so the JSON stays useful.

UI-level (the app must be in the foreground):

1. Add a function to `lib/checks_ui.ps1` and a branch in `Invoke-ReworkChecks` (keeps
   `run_matrix.ps1` readable and keeps every UI check in one file with one selector table).
2. Start from `Start-UiApp` + `Set-UiTestState` so the engine state is known before you touch the UI.
3. Locate widgets through `Get-UiElement` (testTag first, then `Get-AppString` copy) — never a bare
   Chinese literal; if the selector is missing, record `SKIP` with `Format-UiSnapshot` in the detail
   so the next person sees the actual screen.
4. When you assert movement during a gesture, use `Start-JoystickPress` … measure … `Stop-JoystickPress`
   (a `DragJoystick -HoldMs` would release before you can sample).
5. Wrap the body in `Invoke-Guarded` so one broken selector cannot abort the remaining checks.

## Operational gotchas (all of these cost a cycle at least once)

| Symptom | Cause / fix |
|---|---|
| Module silently stops working after a rebuild | `adb install -r` changes `/data/app/~~hash`; LSPosed's `modules.apk_path` goes stale. Re-run `activate_module.ps1 -NoReboot` (it rewrites the row) and restart the scoped apps — and reboot for the `system` scope. |
| `adb install -r -g` did not grant location on a MIUI build | `-g` is not honoured there; always follow up with `pm grant` (`Grant-Permissions` in `lib/common.ps1` does it). |
| Verifier ignores `--ez autotest true` | boolean extras were observed to arrive as `false` on this build; use `--es autotest true` (the harness does). |
| Host commands have no effect while the UI app is running | historical: an older app revision re-pushed its own SimState every ~5 s. The current app only re-pushes on service start/stop and when the user changes a setting (its periodic loop is `Ping`/`GetState` only), so a host `SetConfig` survives — but any UI interaction that changes speed/mode overwrites it, so re-apply the config immediately before an assertion. `run_matrix.ps1` force-stops the app before checks 1–10 (engine-only) and re-applies state in the UI checks. |
| `pm list packages` prints `Can't find service: package` | a system_server restart is in flight; `Wait-PackageService` (used by the matrix) waits it out. |
| Repeated `W ContextImpl: Calling a method in the system process without a qualified user` | harmless: the hook broadcasts from `system_server` without a user-qualified context. |
| A script silently writes into `e2e/out` although it takes a destination folder | the dot-sourced `lib/common.ps1` assigns `$script:OutDir`, and at **script scope** a `-OutDir` parameter *is* that variable, so the library wins. Name such a parameter anything else (`-Destination`). |
| A helper returns one object instead of an array and `.Count` throws | PowerShell unrolls a one-element array on return. Wrap every call: `$b = @(Get-UiBounds …)`. `Set-StrictMode -Version Latest` (set by `common.ps1`) makes this a hard error rather than a silent `$null`. |
| `Variable reference is not valid. ':' was not followed by a valid variable name` | `"$Name: …"` inside a string. PowerShell reads it as a scope-qualified variable — write `"${Name}: …"`. `e2e/lint.ps1` catches this without a device. |
| `-Only 12,13` fails with `Cannot convert value to type System.String` | a `[string]` parameter cannot bind the array form. Both `run_matrix.ps1` and `capture_screens.ps1` declare `-Only` as `[string[]]`, so `-Only 12,13` and `-Only "12,13"` both work. |
| `uiautomator dump` prints `ERROR: could not get idle state` | the UI is still animating; `Get-UiXml` retries (3 attempts, 700 ms apart) instead of returning a half-parsed tree. |
| A joystick swipe moves the position but the direction is unmeasurable | `input swipe` ramps the vector and releases at the end, so the engine only sees it for a few hundred ms — but it is the **only** form that works: `adb shell input motionevent DOWN/MOVE/UP` is three separate processes, i.e. three input streams, and Compose receives a *cancelled* gesture, so the app never sends a non-zero `SetJoystick` at all. `Start-JoystickPress` therefore launches one long `input swipe` **in the background** and the check samples while it runs. |
| Every movement check reports `0 m / 0 samples` right after the hygiene gate | **two separate causes, both harness-side.** (1) The gate used to `force-stop` **our own app** — which owns the foreground service and keeps the simulation alive — so the gate measured its own side effect. (2) Even with a correct gate, `dumpsys location \| grep immoral.` is the wrong oracle: that bundle only exists for a short while after a fix was delivered to a listener, and it was observed empty while the engine was demonstrably injecting. The gate now stops only foreign spoofers, keeps our app alive, and proves injection with a **VERIFY `location` line within 80 m of the test point** (i.e. it travelled `requestLocationUpdates`). |
| A joystick drag moves nothing although the engine is fine | the synthetic gesture was swallowed by the pad — it can read as a tap/scroll, especially while the map is still re-centring after the preceding `SetPosition`. The app is the only honest witness: poll the hook log for `decoded: SetJoystick` after starting the drag and retry once if it never appears. Evidence that 0 m legs are gesture faults and not direction bugs: the one leg that did arrive measured **277.3° for a leftward drag (expected 270°, error 7.3°)** with 15 samples. |
| **A tap "does nothing" although the same coordinates work by hand** | **RULE: any check that starts another app before synthesising input must first restore and confirm its own foreground.** `Start-Verifier` launches the verifier activity, which takes the foreground; the following `input tap` then lands on *that* window while `uiautomator dump` still describes our own UI — so bounds look perfect and the tap silently does nothing. Restore with `Start-App`, then poll the dump until at least one of our own `e2e.*` ids is present, and only then tap. Confirm the tap landed by the effect it must have (`command decoded: SetPosition`, a snackbar, a new sample), never by the absence of an error. |
| **Every position row reads `no-sample` right after a `Force-Stop` + relaunch** | **RULE (the counterpart of the one above): the verifier only delivers fixes while it is foreground.** Relaunching our app to keep the broadcast channel alive puts the verifier in the background, where its callbacks starve — so a `Force-Stop` → `Start-App` sequence trades a dead control channel for a dead location stream. After the relaunch, force a fresh verifier stream (`Ensure-VerifierStream -Force`) before measuring; checks 5 and 6 do exactly this. Both halves are needed: relaunch for the channel, re-foreground the verifier for the samples. |
| **A row passes but the run silently used the prefs channel** | every result row now carries `channel = broadcast | prefs-fallback(N)`, and the run ends with a `harness-control-channel (DEGRADED)` WARN naming the first/last fallback time. A prefs-fallback run is not a broadcast-channel run: read the per-row stamp before trusting any motion number from it. |

## Next oracle (designed, not yet wired)

`dumpsys location`, the verifier stream and log parsing have each lied at least once. The engine state is
also written by the framework layer to the app's `filesDir/fw_status.json` at ~1 Hz — a serialized
`EngineState` carrying `point` / `speedMps` / `bearingDeg` / `mode` / `running` / `routeProgress`, i.e.
exactly the values the UI renders. It is written **only** by the `system_server` layer (no second writer
to race with) and is readable as root without a broadcast receiver.

**It is authoritative only while fresh, and the freshness rule is `mtime`, not the payload**:
`FrameworkStatus.read()` rejects it when `now - file.lastModified() > 3_500 ms` (and the app falls back
to the last broadcast reply), so a harness that parses it blindly will read a stale-but-plausible state
from a paused engine — the `dumpsys` bug with a nicer shape. Correct handling: treat `mtime ≤ 3.5 s`
as "data", anything older as "no data"; cross-check the payload's `updatedAtMs` against the device wall
clock if portability matters. Sample `filesDir/hook_heartbeat` (freshness 30 s) alongside it, so
"engine moved but the module went quiet" stays distinguishable from "the harness read nothing".

Plan: record it as an extra metric beside the current verdict for one round, confirm it agrees with the
engine's `moved=B` tick line, then promote it to the deciding assertion for checks `12`/`12b`.

## Troubleshooting (hook / device level)

| Symptom | Cause / fix |
|---|---|
| `no framework-layer lines in logcat` | scope missing `system`, or the module was never loaded into a fresh `system_server`. Run `activate_module.ps1` and reboot. A cleared log buffer also hides the bootstrap lines — rely on the functional check (test 1). |
| VERIFY lines missing entirely | verifier not installed / permissions not granted: `install.ps1` or `Grant-Permissions`. |
| logcat seems truncated | the matrix enlarges buffers with `logcat -G 4M`; check `adb logcat -g`. |
| cell spoofing silently dead (`6-cells` A→C unchanged) | **scope must include `com.android.phone`** — it is the platform component that hosts `PhoneInterfaceManager`, the API every app's `getAllCellInfo` goes through, and where `framework/TelephonyHooks` installs. `-Prune` with `android` alone drops it and nothing looks broken until the check reads identical cells before and after. `com.android.phone` is an OS component, so scoping it is still "framework only, never hook the target app". |
| every position check reads `0 m / 0 samples` with a *plausible-looking* verifier app | `Ensure-VerifierStream` used to accept "activity started" as "stream alive"; it now requires a real `VERIFY location` line within 10 s, restarts once, and prints explicit guidance. If it still reports not delivering, check `pm grant` + `appops mock_location` before blaming the product. |
| a drift/speed measurement reads `0 m/s` or an absurd `67 m/s` while the engine is clearly moving | the verifier's delivered stream is sparse when the verifier is backgrounded (2–8 samples per 8 s window). Use the engine's own tick log (`Get-EngineTicks` / `Measure-EngineTicks`, reported as `src=engine-tick`): it is unthrottled and reports exactly `speed`/`moved` per applied interval. With it, all six speed levels measure at ratio 1.00. |

