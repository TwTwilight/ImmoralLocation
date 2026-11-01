// Native hook for the sensor event path.
//
// Why native at all: the step counter/detector are served by SensorService (native, inside
// system_server) and pushed to every app through a per-connection socket. There is no Java
// choke point in system_server, which is why the Java-only implementation had to hook each
// target app's own process. Hooking `SensorEventConnection::sendEvents` here spoofs steps for
// *every* app with a single injection, so the module can run with scope = system only.
//
// Two things happen in the hook, and they are **not** the same technique:
//
//  1. **Rewrite** (`rewriteSteps`, always installed): step events the HAL produced are replaced
//     with the engine's counter. This is the accurate path and the only one that needs no
//     knowledge about the connection's sensor list.
//  2. **Inject** (`injectStepEvent`, needs a sensor handle): `TYPE_STEP_COUNTER` reports on
//     *change*, so a phone lying on a desk (the acceptance harness) produces **no** step events
//     and there is nothing to rewrite — measured on device as `counter delta=0 over 0 samples`
//     while the engine counter was climbing. Rewriting alone can therefore never satisfy
//     步频模拟 on a stationary device; the counter has to be fabricated. We build one
//     `sensors_event_t` carrying a step-counter handle and hand it to the *original*
//     `sendEvents` for the same connection; SensorService's own per-connection filter drops it
//     for every app that did not register that sensor, so no other sensor stream is affected.
//     The handles are not assumed: Java pushes *every* `TYPE_STEP_COUNTER` handle it can see with
//     `nativeSetStepSensors`, and the first real step event observed here is treated as the
//     authoritative handle for this system (this device exposes a non-wakeup and a wake-up step
//     counter, and injecting only the default one was measured to reach no app at all).
//
// Failure policy: every path is guarded and degrades to "no synthetic steps" (the rewrite path is
// untouched) — nothing here may throw into, or crash, system_server.
//
// Symbol (exported by libsensorservice.so on Android 15):
//   android::SensorService::SensorEventConnection::sendEvents(
//       sensors_event_t const*, size_t, sensors_event_t*, android::wp<...> const*)

#include <android/sensor.h>
#include <dlfcn.h>
#include <jni.h>
#include <time.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include "log.h"
#include "sensor_hook.h"
#include "elf_symbol.h"
#include "state.h"

#include "shadowhook.h"

namespace il {

namespace {

// Mangled name of SensorEventConnection::sendEvents on API 30..35 (Itanium ABI, stable).
constexpr const char *kSendEventsSym =
    "_ZN7android13SensorService21SensorEventConnection10sendEventsEPK15sensors_event_tmPS2_PKNS_2wpIKS1_EE";

constexpr const char *kSensorServiceLib = "libsensorservice.so";

// `sensors_event_t` is the framework-side name for this struct; the NDK only ships ASensorEvent,
// which is layout-identical (see android/sensor.h). Keep one alias so the ABI stays obvious.
using SensorEvent = ASensorEvent;

using SendEventsFn = void (*)(void *self, const SensorEvent *buffer, size_t numEvents,
                              SensorEvent *scratch, const void *wpConnection);

SendEventsFn orig_sendEvents = nullptr;
std::atomic<bool> installed{false};
std::string lastError;

// --- fabrication state -------------------------------------------------------------------------
// Candidate HAL handles of `TYPE_STEP_COUNTER`, filled from two independent sources:
//   * Java (`SensorBoot` -> `nativeSetStepSensors`), which asks the system context's SensorManager
//     for *every* sensor of that type — this device exposes both a non-wakeup (`0xbf`) and a
//     wake-up (`0xc0`) step counter, and `getDefaultSensor` only ever returns one of them;
//   * observation: the first real step event that passes through `sendEvents` carries the exact
//     handle the apps in this system registered, which is authoritative.
// All candidates are tried on every cadence tick; `SensorService`'s per-connection filter keeps
// whichever one the receiving app actually registered. Measured on device (2026-11-02): injecting
// only the `getDefaultSensor` handle produced 27 injections and zero app-visible events, which is
// why the candidate set exists.
constexpr int kMaxStepHandles = 6;
std::atomic<int32_t> stepHandles[kMaxStepHandles];
std::atomic<int32_t> stepHandleCount{0};
std::atomic<int32_t> observedStepHandle{-1};
std::atomic<int32_t> injectedHandleLast{0};

// Timestamp of the last fabricated event (CLOCK_BOOTTIME ns); throttles fabrication to the
// configured cadence so a 100 Hz sendEvents stream cannot turn into 100 steps per second.
std::atomic<int64_t> lastInjectNanos{0};

std::atomic<int64_t> injectedEvents{0};
std::atomic<int64_t> injectedSkipped{0};

int64_t nowBoottimeNanos() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) return 0;
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}

/** Adds a candidate handle (idempotent, bounded, lock-free enough: no allocation, no blocking). */
void addStepHandle(int32_t handle) {
    if (handle < 0) return;
    const int32_t n = stepHandleCount.load(std::memory_order_acquire);
    for (int32_t i = 0; i < n; ++i) {
        if (stepHandles[i].load(std::memory_order_relaxed) == handle) return;
    }
    if (n >= kMaxStepHandles) return;
    stepHandles[n].store(handle, std::memory_order_relaxed);
    // Publish only after the slot is written; readers may see the new count one call early at worst.
    stepHandleCount.store(n + 1, std::memory_order_release);
}

/**
 * Learns the real step-counter handle from the HAL's own events.
 *
 * Runs **before** the `steps > 0` gate in [rewriteSteps] on purpose: at the moment the first real
 * step event arrives the engine may not have counted anything yet, and that event is exactly the
 * piece of information that tells us which handle the apps in this system are registered for.
 */
void observeStepHandles(const SensorEvent *events, size_t count) {
    if (events == nullptr || count == 0) return;
    for (size_t i = 0; i < count; ++i) {
        if (events[i].type != ASENSOR_TYPE_STEP_COUNTER) continue;
        const int32_t handle = events[i].sensor;
        int32_t previous = observedStepHandle.load(std::memory_order_relaxed);
        if (previous == handle) continue;
        observedStepHandle.store(handle, std::memory_order_relaxed);
        addStepHandle(handle);
        logi("observed a real step_counter event on handle %d (now %d candidate handle(s))", handle,
             static_cast<int>(stepHandleCount.load(std::memory_order_acquire)));
    }
}

/**
 * Rewrites step-related events in place.
 *
 * The buffer is owned by SensorService and is writable memory even though the parameter is
 * const, so the cast is the intended technique for this hook point (same approach as other
 * open-source sensor hooks). Only the two step types are touched; every other sensor event is
 * passed through untouched so third-party apps keep working normally.
 */
void rewriteSteps(SensorEvent *events, size_t count) {
    observeStepHandles(events, count);
    StepState &state = stepState();
    if (!state.enabled.load(std::memory_order_relaxed)) return;

    const double steps = state.steps.load(std::memory_order_relaxed);
    if (steps <= 0.0) return;
    const int64_t stepsI64 = static_cast<int64_t>(steps);

    HookStats &stats = hookStats();
    for (size_t i = 0; i < count; ++i) {
        SensorEvent &event = events[i];
        switch (event.type) {
            case ASENSOR_TYPE_STEP_COUNTER:
                // Counter semantics: monotonically increasing total since boot.
                // Android 12+ delivers the step COUNTER as an integer in the union (the Java
                // side reads it as a whole number), so write 64 bits instead of a float.
                memcpy(event.data, &stepsI64, sizeof(stepsI64));
                stats.counterRewrites.fetch_add(1, std::memory_order_relaxed);
                break;
            case ASENSOR_TYPE_STEP_DETECTOR:
                // Detector semantics: 1.0 per detected step.
                event.data[0] = 1.0f;
                stats.detectorRewrites.fetch_add(1, std::memory_order_relaxed);
                break;
            default:
                break;
        }
    }
}

/**
 * Fabricates step-counter events and hands them to the original `sendEvents` for **this** connection.
 *
 * `self` is the connection whose `sendEvents` invocation we are inside, so the event goes to exactly
 * the connection that is being served — not to some other app's. `SensorService`'s own per-connection
 * filter then keeps the event only if the receiving app registered *that* handle, so an app without
 * `TYPE_STEP_COUNTER` never sees it and no other sensor stream is affected.
 *
 * One event per candidate handle per cadence tick: this device exposes a non-wakeup (`0xbf`) and a
 * wake-up (`0xc0`) step counter, and `getDefaultSensor` returns only one of them. Injecting the
 * single default handle was measured to produce 27 injections and zero app-visible events, hence the
 * candidate set (see [addStepHandles] / `nativeSetStepSensors`).
 *
 * Skipped entirely when the batch already contains a step-counter event: then [rewriteSteps] has
 * already replaced its value and the HAL's own event wins.
 */
void injectStepEvent(void *self, const SensorEvent *batch, size_t count, SensorEvent *scratch,
                     const void *wpConnection) {
    if (orig_sendEvents == nullptr) return;
    StepState &state = stepState();
    if (!state.enabled.load(std::memory_order_relaxed)) return;

    const int32_t handleCount = stepHandleCount.load(std::memory_order_acquire);
    if (handleCount <= 0) return;

    const double steps = state.steps.load(std::memory_order_relaxed);
    if (steps <= 0.0) return;

    for (size_t i = 0; i < count; ++i) {
        if (batch != nullptr && batch[i].type == ASENSOR_TYPE_STEP_COUNTER) return;
    }

    int32_t cadence = state.cadenceBpm.load(std::memory_order_relaxed);
    if (cadence < 60) cadence = 60;
    if (cadence > 240) cadence = 240;
    const int64_t minGapNanos = 60LL * 1000000000LL / static_cast<int64_t>(cadence);

    const int64_t now = nowBoottimeNanos();
    if (now <= 0) return;
    int64_t last = lastInjectNanos.load(std::memory_order_relaxed);
    if (last != 0 && now - last < minGapNanos) {
        injectedSkipped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (!lastInjectNanos.compare_exchange_strong(last, now)) return;

    const int64_t stepsI64 = static_cast<int64_t>(steps);
    SensorEvent localScratch{};
    SensorEvent *scratchToUse = scratch != nullptr ? scratch : &localScratch;
    int32_t lastHandle = -1;
    for (int32_t i = 0; i < handleCount && i < kMaxStepHandles; ++i) {
        const int32_t handle = stepHandles[i].load(std::memory_order_relaxed);
        if (handle < 0) continue;
        SensorEvent event{};
        // `version` is documented as `sizeof(struct ASensorEvent)` in the NDK header, which is what
        // a HAL fills in; it is not a protocol version. `flags`/`reserved*` stay 0: this event is
        // not offloaded and carries no wake-up marker of its own.
        event.version = static_cast<int32_t>(sizeof(SensorEvent));
        event.sensor = handle;
        event.type = ASENSOR_TYPE_STEP_COUNTER;
        event.timestamp = now;
        memcpy(event.data, &stepsI64, sizeof(stepsI64));
        // The original (not the hook): no recursion, and the real batch was already delivered.
        orig_sendEvents(self, &event, 1, scratchToUse, wpConnection);
        lastHandle = handle;
        injectedEvents.fetch_add(1, std::memory_order_relaxed);
    }
    injectedHandleLast.store(lastHandle, std::memory_order_relaxed);

    const int64_t total = injectedEvents.load(std::memory_order_relaxed);
    if (total <= static_cast<int64_t>(handleCount) || total % 120 == 0) {
        logi("injected step event value=%lld handles=%d last=%d cadence=%d total=%lld",
             static_cast<long long>(stepsI64), static_cast<int>(handleCount), lastHandle, cadence,
             static_cast<long long>(total));
    }
}

void sendEventsHook(void *self, const SensorEvent *buffer, size_t numEvents,
                    SensorEvent *scratch, const void *wpConnection) {
    hookStats().sendEventsCalls.fetch_add(1, std::memory_order_relaxed);
    if (buffer != nullptr && numEvents > 0) {
        // const_cast is deliberate: the framework owns this buffer and writes it right after us.
        rewriteSteps(const_cast<SensorEvent *>(buffer), numEvents);
    }
    if (orig_sendEvents != nullptr) {
        orig_sendEvents(self, buffer, numEvents, scratch, wpConnection);
    }
    // Fabrication happens after the real batch, so a HAL-produced event always wins.
    injectStepEvent(self, buffer, numEvents, scratch, wpConnection);
}

/** Resolves `sendEvents` in the system_server's own copy of libsensorservice.so. */
void *resolveSendEvents() {
    // 1) Namespace-free path first: system_server maps libsensorservice.so inside an isolated
    //    linker namespace, where dlopen("libsensorservice.so", RTLD_NOLOAD) fails even though the
    //    library is loaded. dl_iterate_phdr sees it regardless of namespace.
    void *addr = lookupSymbolInLoadedModule(kSensorServiceLib, kSendEventsSym);
    if (addr != nullptr) return addr;

    lastError = "libsensorservice.so not resolvable via dl_iterate_phdr";

    // 2) Fallback for processes where a plain dlopen does work.
    void *handle = dlopen(kSensorServiceLib, RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen(kSensorServiceLib, RTLD_NOW);
    }
    if (handle == nullptr) {
        const char *err = dlerror();
        lastError += std::string("; dlopen: ") + (err ? err : "?");
        return nullptr;
    }
    addr = dlsym(handle, kSendEventsSym);
    if (addr == nullptr) {
        const char *err = dlerror();
        lastError = std::string("dlsym sendEvents: ") + (err ? err : "symbol not exported");
    }
    return addr;
}

}  // namespace

const std::string &lastInstallError() { return lastError; }

/** Installs the native hooks. Idempotent; safe to call from system_server only. */
bool installSensorHooks() {
    if (installed.exchange(true, std::memory_order_acq_rel)) return true;
    lastError.clear();

    int rc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (rc != 0) {
        lastError = "shadowhook_init rc=" + std::to_string(rc);
        loge("%s", lastError.c_str());
        installed.store(false, std::memory_order_release);
        return false;
    }

    void *addr = resolveSendEvents();
    if (addr == nullptr) {
        hookStats().lookedUpFailures.fetch_add(1, std::memory_order_relaxed);
        loge("%s", lastError.c_str());
        installed.store(false, std::memory_order_release);
        return false;
    }

    void *stub = shadowhook_hook_sym_addr(addr, reinterpret_cast<void *>(&sendEventsHook),
                                          reinterpret_cast<void **>(&orig_sendEvents));
    if (stub == nullptr) {
        hookStats().lookedUpFailures.fetch_add(1, std::memory_order_relaxed);
        const int err = shadowhook_get_errno();
        lastError = std::string("shadowhook_hook_sym_addr: ") + shadowhook_to_errmsg(err);
        loge("%s (addr=%p errno=%d)", lastError.c_str(), addr, err);
        installed.store(false, std::memory_order_release);
        return false;
    }

    logi("hooked SensorEventConnection::sendEvents addr=%p orig=%p stub=%p", addr,
         reinterpret_cast<void *>(orig_sendEvents), stub);
    return true;
}

bool hooksInstalled() { return installed.load(std::memory_order_acquire); }

/**
 * Replaces the candidate `TYPE_STEP_COUNTER` handles with [handles] (from Java).
 *
 * The handle observed from a real HAL step event is kept on top: it is the authoritative one for the
 * apps in this system, while Java's `getSensorList` is the only source that can name the *other*
 * variant (non-wakeup vs wake-up) before any step has ever happened.
 */
void setStepSensorHandles(const int32_t *handles, int32_t count) {
    stepHandleCount.store(0, std::memory_order_release);
    for (int32_t i = 0; i < count && i < kMaxStepHandles; ++i) addStepHandle(handles[i]);
    const int32_t observed = observedStepHandle.load(std::memory_order_relaxed);
    if (observed >= 0) addStepHandle(observed);
}

/** One-line state of the fabrication half, for the Java seam diagnostic. */
std::string stepSeamSummary() {
    char buf[256];
    const int32_t n = stepHandleCount.load(std::memory_order_acquire);
    char handles[64] = {0};
    size_t used = 0;
    for (int32_t i = 0; i < n && i < kMaxStepHandles; ++i) {
        const int written = snprintf(handles + used, sizeof(handles) - used, "%s%d", i == 0 ? "" : ",",
                                    stepHandles[i].load(std::memory_order_relaxed));
        if (written <= 0) break;
        used += static_cast<size_t>(written);
        if (used >= sizeof(handles) - 1) break;
    }
    snprintf(buf, sizeof(buf),
             "handles=[%s] observed=%d last=%d injected=%lld skipped=%lld enabled=%d steps=%.0f cadence=%d",
             handles, observedStepHandle.load(std::memory_order_relaxed),
             injectedHandleLast.load(std::memory_order_relaxed),
             static_cast<long long>(injectedEvents.load(std::memory_order_relaxed)),
             static_cast<long long>(injectedSkipped.load(std::memory_order_relaxed)),
             stepState().enabled.load(std::memory_order_relaxed) ? 1 : 0,
             stepState().steps.load(std::memory_order_relaxed),
             stepState().cadenceBpm.load(std::memory_order_relaxed));
    return std::string(buf);
}

}  // namespace il

// --- JNI surface of the step seam ---------------------------------------------------------------
// Deliberately defined here instead of jni_bridge.cpp: this file owns the fabrication state, so the
// sensor handles and the counters never have to be exported through another translation unit.

extern "C" JNIEXPORT void JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeSetStepSensors(JNIEnv *env, jclass, jintArray handles) {
    if (handles == nullptr) {
        il::setStepSensorHandles(nullptr, 0);
        il::logi("step seam: no step-counter handles; fabrication disarmed");
        return;
    }
    const jsize length = env->GetArrayLength(handles);
    jint values[il::kMaxStepHandles];
    const jsize capped = length < il::kMaxStepHandles ? length : il::kMaxStepHandles;
    if (capped <= 0) {
        il::logi("step seam: empty step-counter handle array; fabrication disarmed");
        return;
    }
    // `GetIntArrayRegion` returns void in this JNI header; a failure surfaces as a pending exception.
    env->GetIntArrayRegion(handles, 0, capped, values);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        il::logi("step seam: cannot read the step-counter handle array");
        return;
    }
    il::setStepSensorHandles(values, static_cast<int32_t>(capped));
    il::logi("step seam: %d step-counter handle(s) from Java, fabrication armed", static_cast<int>(capped));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeStepSeam(JNIEnv *env, jclass) {
    return env->NewStringUTF(il::stepSeamSummary().c_str());
}
