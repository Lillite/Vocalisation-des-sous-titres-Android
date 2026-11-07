#pragma once
#include <atomic>
#include <cstdint>

// Numeric, process-private observations. They do not include subtitle text,
// tensor names or paths and must not be added to RSS as independent memory.
namespace subtitle_native::opencl_memory {
struct Snapshot {
    uint64_t scratch_current_bytes, scratch_peak_bytes, upload_peak_bytes;
    uint64_t allocation_failures, host_fallbacks, pool_entries, pool_bytes;
    uint64_t skipped_initial_bytes;
};
struct Counters {
    std::atomic<uint64_t> scratch{0}, scratch_peak{0}, upload_peak{0};
    std::atomic<uint64_t> failures{0}, fallbacks{0}, pool_entries{0}, pool_bytes{0}, skipped{0};
};
inline Counters & counters() { static Counters value; return value; }
inline void peak(std::atomic<uint64_t> & target, uint64_t value) noexcept {
    auto previous = target.load(std::memory_order_relaxed);
    while (previous < value && !target.compare_exchange_weak(previous, value, std::memory_order_relaxed)) { }
}
inline void scratch_allocate(uint64_t bytes) noexcept {
    auto & value = counters();
    peak(value.scratch_peak, value.scratch.fetch_add(bytes, std::memory_order_relaxed) + bytes);
}
inline void scratch_release(uint64_t bytes) noexcept {
    counters().scratch.fetch_sub(bytes, std::memory_order_relaxed);
}
inline void upload(uint64_t bytes) noexcept { peak(counters().upload_peak, bytes); }
inline void failed() noexcept { counters().failures.fetch_add(1, std::memory_order_relaxed); }
inline void host_fallback() noexcept { counters().fallbacks.fetch_add(1, std::memory_order_relaxed); }
inline void pools(uint64_t entries, uint64_t covered_bytes) noexcept {
    // covered_bytes includes aliased views: this is not an independent RSS allocation.
    counters().pool_entries.store(entries, std::memory_order_relaxed);
    counters().pool_bytes.store(covered_bytes, std::memory_order_relaxed);
}
inline void skipped(uint64_t bytes) noexcept { counters().skipped.fetch_add(bytes, std::memory_order_relaxed); }
inline Snapshot snapshot() noexcept {
    auto & v = counters();
    return {v.scratch.load(), v.scratch_peak.load(), v.upload_peak.load(), v.failures.load(),
            v.fallbacks.load(), v.pool_entries.load(), v.pool_bytes.load(), v.skipped.load()};
}
inline void reset_peaks() noexcept {
    auto & v = counters();
    v.scratch_peak.store(v.scratch.load()); v.upload_peak.store(0);
    v.failures.store(0); v.fallbacks.store(0); v.skipped.store(0);
}
}
