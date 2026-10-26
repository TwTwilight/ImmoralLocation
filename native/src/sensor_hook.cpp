// Native hook for the sensor event path.
//
// Why native at all: the step counter/detector are served by SensorService (native, inside
// system_server) and pushed to every app through a per-connection socket. There is no Java
// choke point in system_server, which is why the Java-only implementation had to hook each
// target app's own process. Hooking `SensorEventConnection::sendEvents` here spoofs steps for
// *every* app with a single injection, so the module can run with scope = system only.
//
// Symbol (exported by libsensorservice.so on Android 15):
//   android::SensorService::SensorEventConnection::sendEvents(
//       sensors_event_t const*, size_t, sensors_event_t*, android::wp<...> const*)

#include <android/sensor.h>
#include <dlfcn.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
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

}  // namespace il
