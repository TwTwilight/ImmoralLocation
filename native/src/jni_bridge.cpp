// JNI surface of the native layer.
//
// The Java side (com.immoral.location.hook.NativeBridge) loads this library from the LSPosed
// module APK inside system_server, installs the hooks once, and pushes the engine's step state
// on every tick. Nothing else crosses the boundary, which keeps the hot path (sendEvents) fully
// native.

#include <jni.h>

#include <cstdint>
#include <string>

#include "log.h"
#include "sensor_hook.h"
#include "state.h"

namespace {

std::string hookSummary() {
    il::HookStats &s = il::hookStats();
    char buf[256];
    snprintf(buf, sizeof(buf), "installed=%d calls=%lld counter=%lld detector=%lld lookup_fail=%lld",
             il::hooksInstalled() ? 1 : 0, static_cast<long long>(s.sendEventsCalls.load()),
             static_cast<long long>(s.counterRewrites.load()),
             static_cast<long long>(s.detectorRewrites.load()),
             static_cast<long long>(s.lookedUpFailures.load()));
    return std::string(buf);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    il::logi("libimmoralhook loaded (abi=%s)", sizeof(void *) == 8 ? "arm64-v8a" : "armeabi-v7a");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeInit(JNIEnv *, jclass) {
    return il::installSensorHooks() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeSetStepState(JNIEnv *, jclass, jlong steps,
                                                              jint cadenceBpm, jboolean enabled) {
    il::StepState &s = il::stepState();
    s.steps.store(static_cast<double>(steps), std::memory_order_relaxed);
    s.cadenceBpm.store(static_cast<int32_t>(cadenceBpm), std::memory_order_relaxed);
    s.enabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
    s.sequence.fetch_add(1, std::memory_order_relaxed);
    s.pushedAtMs.store(static_cast<int64_t>(0), std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeStats(JNIEnv *env, jclass) {
    return env->NewStringUTF(hookSummary().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeLastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(il::lastInstallError().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_immoral_location_hook_NativeBridge_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF("immoralhook/1.0.0 (shadowhook)");
}
