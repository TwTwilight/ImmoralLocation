// Shared simulation state for the native layer.
//
// The Java engine (system_server side) owns the authoritative values and pushes them here on
// every tick; the native hook only reads them while rewriting sensor events, so everything is
// lock-free atomics and the hook can never block on the Java side.
#pragma once

#include <atomic>
#include <cstdint>

namespace il {

struct StepState {
    std::atomic<double> steps{0.0};      // authoritative cumulative step count
    std::atomic<int32_t> cadenceBpm{120};
    std::atomic<bool> enabled{false};    // step simulation on/off
    std::atomic<int64_t> sequence{0};    // bumped by every push, lets the hook detect freshness
    std::atomic<int64_t> pushedAtMs{0};
};

inline StepState &stepState() {
    static StepState s;
    return s;
}

struct HookStats {
    std::atomic<int64_t> sendEventsCalls{0};
    std::atomic<int64_t> counterRewrites{0};
    std::atomic<int64_t> detectorRewrites{0};
    std::atomic<int64_t> lookedUpFailures{0};
};

inline HookStats &hookStats() {
    static HookStats s;
    return s;
}

}  // namespace il
