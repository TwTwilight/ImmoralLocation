// Minimal logging for the native layer: everything goes to logcat with one tag so the
// on-device evidence for a hook install is a single grep away.
#pragma once

#include <android/log.h>

namespace il {

constexpr const char *kTag = "ImmoralNative";

template <typename... Args>
inline void logi(const char *fmt, Args... args) {
    __android_log_print(ANDROID_LOG_INFO, kTag, fmt, args...);
}

template <typename... Args>
inline void logw(const char *fmt, Args... args) {
    __android_log_print(ANDROID_LOG_WARN, kTag, fmt, args...);
}

template <typename... Args>
inline void loge(const char *fmt, Args... args) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, fmt, args...);
}

}  // namespace il
