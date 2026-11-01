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
//     `sensors_event_t` carrying the step-counter handle and hand it to the *original*
//     `sendEvents` for the same connection; SensorService's own per-connection filter drops it
//     for every app that did not register that sensor, so no other sensor stream is affected.
//     The handle is not assumed: Java resolves it (`Sensor.getHandle()`) and pushes it with
//     `nativeSetStepSensor`; a handle learned from a real step event is used as a fallback.
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
// Handle of `TYPE_STEP_COUNTER` in the HAL's numbering, or -1 when unknown. Pushed by Java
// (`SensorBoot` resolves it through the system context's SensorManager) or learned from a real
// event for that sensor, whichever happens first.
std::atomic<int32_t> stepCounterHandle{-1};

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

/**
 * Rewrites step-related events in place.
 *
 * The buffer is owned by SensorService and is writable memory even though the parameter is
 * const, so the cast is the intended technique for this hook point (same approach as other
 * open-source sensor hooks). Only the two step types are touched; every other sensor event is
 * passed through untouched so third-party apps keep working normally.
 */
void rewriteSteps(SensorEvent *events, size_t count) {
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
                // Remember the handle: it is the only way to fabricate an event for an app that
                // never receives one (see injectStepEvent).
                if (stepCounterHandle.load(std::memory_order_relaxed) < 0) {
                    stepCounterHandle.store(event.sensor, std::memory_order_relaxed);
                }
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
 * Fabricates one step-counter event and hands it to the original `sendEvents` for this connection.
 *
 * Only runs when the batch contains **no** step-counter event (otherwise `rewriteSteps` already
 * did the right thing) and at most once per `60s / cadenceBpm`. The event carries the HAL handle of
 * the step counter, so the connection's own filter decides whether the app sees it — an app that
 * did not register `TYPE_STEP_COUNTER` never gets it, and no other sensor is affected.
 */
void injectStepEvent(void *self, const SensorEvent *batch, size_t count, SensorEvent *scratch,
                     const void *wpConnection) {
    if (orig_sendEvents == nullptr) return;
    StepState &state = stepState();
    if (!state.enabled.load(std::memory_order_relaxed)) return;

    const int32_t handle = stepCounterHandle.load(std::memory_order_relaxed);
    if (handle < 0) return;

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

    SensorEvent event{};
    // `version` is documented as `sizeof(struct ASensorEvent)` in the NDK header, which is what a
    // HAL fills in; it is not a protocol version. `flags`/`reserved*` stay 0: this event is not
    // offloaded and carries no wake-up marker of its own.
    event.version = static_cast<int32_t>(sizeof(SensorEvent));
    event.sensor = handle;
    event.type = ASENSOR_TYPE_STEP_COUNTER;
    event.timestamp = now;
    const int64_t stepsI64 = static_cast<int64_t>(steps);
    memcpy(event.data, &stepsI64, sizeof(stepsI64));

    SensorEvent localScratch{};
    SensorEvent *scratchToUse = scratch != nullptr ? scratch : &localScratch;
    // The original (not the hook): no recursion, and the real batch was already delivered.
    orig_sendEvents(self, &event, 1, scratchToUse, wpConnection);

    const int64_t total = injectedEvents.fetch_add(1, std::memory_order_relaxed) + 1;
    if (total == 1 || total % 60 == 0) {
        logi("injected step event #%lld value=%lld handle=%d cadence=%d",
             static_cast<long long>(total), static_cast<long long>(stepsI64), handle, cadence);
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

/** Handle of `TYPE_STEP_COUNTER` used for fabrication; `-1` clears it. */
void setStepSensorHandle(int32_t handle) {
    stepCounterHandle.store(handle, std::memory_order_relaxed);
}

/** One-line state of the fabrication half, for the Java seam diagnostic. */
std::string stepSeamSummary() {
    char buf[192];
    snprintf(buf, sizeof(buf), "handle=%d injected=%lld skipped=%lld enabled=%d steps=%.0f cadence=%d",
             stepCounterHandle.load(std::memory_order_relaxed),
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
// sensor handle and the counters never have to be exported through another translation unit.

extern "C" JNIEXPORT void JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeSetStepSensor(JNIEnv *, jclass, jint handle) {
    il::setStepSensorHandle(static_cast<int32_t>(handle));
    il::logi("step seam: step-counter handle set to %d (fabrication %s)", static_cast<int>(handle),
             handle >= 0 ? "armed" : "disarmed");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeStepSeam(JNIEnv *env, jclass) {
    return env->NewStringUTF(il::stepSeamSummary().c_str());
}
