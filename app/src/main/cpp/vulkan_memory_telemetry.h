#pragma once
#include <atomic>
#include <array>
#include <cstdint>
#include <limits>
#include <mutex>

namespace subtitle_native {
struct VulkanBudgetHeap {
    uint32_t index = 0;
    uint64_t budget_bytes = 0;
    uint64_t usage_bytes = 0;
    uint64_t free_bytes = 0;
};
struct VulkanMemorySnapshot {
    bool observed = false;
    uint64_t allocated_bytes = 0;
    uint64_t peak_allocated_bytes = 0;
    uint64_t allocations = 0;
    uint64_t allocation_failures = 0;
    uint64_t largest_allocation_bytes = 0;
    uint64_t imported_bytes = 0;
    uint64_t host_visible_bytes = 0;
    bool budget_observed = false;
    bool budget_extension = false;
    uint64_t budget_bytes = 0;
    uint64_t heap_usage_bytes = 0;
    uint64_t budget_free_bytes = 0;
    uint32_t budget_heap_count = 0;
    std::array<VulkanBudgetHeap, 16> budget_heaps{};
};

// At most sixteen devices/heaps are retained. A missing extension or failed
// refresh never turns the backend's capacity fallback into available memory.
class VulkanBudgetCache {
    std::mutex mutex_;
    std::array<VulkanMemorySnapshot, 16> devices_{};
    static bool add(uint64_t & total, uint64_t value) noexcept {
        constexpr uint64_t limit = static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
        if (value > limit - total) return false;
        total += value;
        return true;
    }
public:
    void invalidate(int device) noexcept {
        if (device < 0 || device >= static_cast<int>(devices_.size())) return;
        std::lock_guard<std::mutex> lock(mutex_);
        devices_[device] = {};
    }
    void observed(int device, bool extension, uint32_t count, const uint32_t * indices,
                  const uint64_t * budgets, const uint64_t * usages, const uint64_t * free) noexcept {
        if (device < 0 || device >= static_cast<int>(devices_.size())) return;
        VulkanMemorySnapshot next;
        next.budget_observed = true;
        next.budget_extension = extension;
        if (extension) {
            if (count == 0 || count > 16 || !indices || !budgets || !usages || !free) {
                invalidate(device); return;
            }
            uint32_t seen = 0;
            for (uint32_t i = 0; i < count; ++i) {
                if (indices[i] >= 16 || (seen & (1u << indices[i])) ||
                    free[i] != (budgets[i] > usages[i] ? budgets[i] - usages[i] : 0) ||
                    !add(next.budget_bytes, budgets[i]) || !add(next.heap_usage_bytes, usages[i]) ||
                    !add(next.budget_free_bytes, free[i])) {
                    invalidate(device); return;
                }
                seen |= 1u << indices[i];
                next.budget_heaps[i] = {indices[i], budgets[i], usages[i], free[i]};
            }
            next.budget_heap_count = count;
        } else if (count != 0) {
            invalidate(device); return;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        devices_[device] = next;
    }
    void merge(int device, VulkanMemorySnapshot & target) noexcept {
        if (device < 0 || device >= static_cast<int>(devices_.size())) return;
        std::lock_guard<std::mutex> lock(mutex_);
        const auto & source = devices_[device];
        target.budget_observed = source.budget_observed;
        target.budget_extension = source.budget_extension;
        target.budget_bytes = source.budget_bytes;
        target.heap_usage_bytes = source.heap_usage_bytes;
        target.budget_free_bytes = source.budget_free_bytes;
        target.budget_heap_count = source.budget_heap_count;
        target.budget_heaps = source.budget_heaps;
    }
};

// Counts successful complete backend buffer objects, including alignment from
// VkMemoryRequirements. This is not physical residency or total driver memory.
// Imported and host-visible bytes overlap and must never be added to the total.
class VulkanAllocationCounters {
    std::atomic<uint64_t> active_{0}, peak_{0}, allocations_{0}, failures_{0};
    std::atomic<uint64_t> largest_{0}, imported_{0}, host_visible_{0};
    static uint64_t add(std::atomic<uint64_t> & value, uint64_t bytes) noexcept {
        uint64_t before = value.load(std::memory_order_relaxed), after;
        do {
            after = bytes > std::numeric_limits<uint64_t>::max() - before ?
                std::numeric_limits<uint64_t>::max() : before + bytes;
        } while (!value.compare_exchange_weak(before, after, std::memory_order_relaxed));
        return after;
    }
    static void subtract(std::atomic<uint64_t> & value, uint64_t bytes) noexcept {
        uint64_t before = value.load(std::memory_order_relaxed), after;
        do { after = before >= bytes ? before - bytes : 0; }
        while (!value.compare_exchange_weak(before, after, std::memory_order_relaxed));
    }
    static void maximum(std::atomic<uint64_t> & value, uint64_t candidate) noexcept {
        uint64_t before = value.load(std::memory_order_relaxed);
        while (before < candidate && !value.compare_exchange_weak(before, candidate, std::memory_order_relaxed)) { }
    }
public:
    void allocated(uint64_t bytes, bool imported, bool host_visible) noexcept {
        if (!bytes) return;
        maximum(peak_, add(active_, bytes));
        add(allocations_, 1);
        maximum(largest_, bytes);
        if (imported) add(imported_, bytes);
        if (host_visible) add(host_visible_, bytes);
    }
    void released(uint64_t bytes, bool imported, bool host_visible) noexcept {
        if (!bytes) return;
        subtract(active_, bytes);
        if (imported) subtract(imported_, bytes);
        if (host_visible) subtract(host_visible_, bytes);
    }
    void failed() noexcept { add(failures_, 1); }
    VulkanMemorySnapshot snapshot() const noexcept {
        VulkanMemorySnapshot out;
        out.allocations = allocations_.load(std::memory_order_relaxed);
        out.allocation_failures = failures_.load(std::memory_order_relaxed);
        out.observed = out.allocations > 0 || out.allocation_failures > 0;
        out.allocated_bytes = active_.load(std::memory_order_relaxed);
        out.peak_allocated_bytes = peak_.load(std::memory_order_relaxed);
        if (out.peak_allocated_bytes < out.allocated_bytes) out.peak_allocated_bytes = out.allocated_bytes;
        out.largest_allocation_bytes = largest_.load(std::memory_order_relaxed);
        out.imported_bytes = imported_.load(std::memory_order_relaxed);
        out.host_visible_bytes = host_visible_.load(std::memory_order_relaxed);
        // Reads are deliberately non-blocking; allocations can overlap this
        // sample. Keep the component bounds consistent without claiming that
        // these independent atomic samples form an exact simultaneous peak.
        if (out.imported_bytes > out.allocated_bytes) out.imported_bytes = out.allocated_bytes;
        if (out.host_visible_bytes > out.allocated_bytes) out.host_visible_bytes = out.allocated_bytes;
        return out;
    }
};
}
