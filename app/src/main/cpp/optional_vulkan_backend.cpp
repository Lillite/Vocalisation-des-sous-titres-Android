#include "optional_vulkan_backend.h"

#ifdef SUBTITLE_HAS_VULKAN
#include "optional_vulkan_dispatch.h"
#include "optional_vulkan_probe.h"
#include "ggml-vulkan.h"
#include "vulkan_profile_latch.h"
#include <atomic>
#include <cstdlib>
#endif

namespace subtitle_native {
#ifdef SUBTITLE_HAS_VULKAN
namespace {
vulkan_probe::Cache probe;
std::atomic<ggml_backend_dev_t> selected{nullptr};
std::atomic<int> selected_index{-1};
VulkanProfileLatch profile;
VulkanAllocationCounters memory;
VulkanBudgetCache budgets;
struct DriverApi {
    bool load_driver(ExecutionDiagnostic * diagnostic) { return bind_vulkan_driver(diagnostic); }
    bool register_backend(unsigned int * count) {
        auto * registration = ggml_backend_vk_reg();
        if (!registration) return false;
        unsigned int supported = 0;
        ggml_backend_dev_t candidate = nullptr;
        for (size_t i = 0; i < ggml_backend_reg_dev_count(registration); ++i) {
            auto * device = ggml_backend_reg_dev_get(registration, i);
            if (!device) continue;
            const auto type = ggml_backend_dev_type(device);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            if (!candidate) { candidate = device; selected_index.store(static_cast<int>(i), std::memory_order_release); }
                if (supported < 65'535U) ++supported;
            }
        }
        *count = supported;
        if (!candidate) return true;
        ggml_backend_register(registration);
        selected.store(candidate, std::memory_order_release);
        return true;
    }
};
DriverApi driver;
}
bool prepare_vulkan(ExecutionDiagnostic * diagnostic) noexcept {
    return prepare_vulkan(false, diagnostic);
}
bool prepare_vulkan(bool qualcomm_compatibility, ExecutionDiagnostic * diagnostic) noexcept {
    bool configuration_failed = false;
    if (!profile.accept(qualcomm_compatibility, [&](bool value) {
        configuration_failed = setenv("SUBTITLE_VULKAN_QUALCOMM_COMPAT", value ? "1" : "0", 1) != 0;
        return !configuration_failed;
    })) {
        if (diagnostic) {
            *diagnostic = {};
            diagnostic->stage = "VULKAN_DRIVER";
            diagnostic->reason = configuration_failed ? "DRIVER_CONFIGURATION_FAILED" : "PROFILE_REQUIRES_RESTART";
        }
        return false;
    }
    return probe.prepare(driver, diagnostic);
}
ggml_backend_dev_t vulkan_device() noexcept { return selected.load(std::memory_order_acquire); }
VulkanMemorySnapshot vulkan_memory_snapshot(bool refresh_budget) noexcept {
    auto result = memory.snapshot();
    const auto device = selected.load(std::memory_order_acquire);
    const int index = selected_index.load(std::memory_order_acquire);
    if (device && refresh_budget) {
        budgets.invalidate(index);
        try {
            // The hook below captures only EXT values; the upstream fallback
            // free=heap capacity output is intentionally not used by our API.
            size_t ignored_free = 0, ignored_total = 0;
            ggml_backend_dev_memory(device, &ignored_free, &ignored_total);
        } catch (...) { budgets.invalidate(index); }
    }
    budgets.merge(index, result);
    return result;
}
extern "C" void subtitle_vulkan_buffer_allocated(uint64_t bytes, bool imported, bool host_visible) noexcept {
    memory.allocated(bytes, imported, host_visible);
}
extern "C" void subtitle_vulkan_buffer_released(uint64_t bytes, bool imported, bool host_visible) noexcept {
    memory.released(bytes, imported, host_visible);
}
extern "C" void subtitle_vulkan_buffer_allocation_failed() noexcept { memory.failed(); }
extern "C" void subtitle_vulkan_budget_observed(int device, bool extension, uint32_t count,
        const uint32_t * indices, const uint64_t * budget, const uint64_t * usage,
        const uint64_t * free) noexcept {
    budgets.observed(device, extension, count, indices, budget, usage, free);
}
#else
bool prepare_vulkan(ExecutionDiagnostic * diagnostic) noexcept {
    return prepare_vulkan(false, diagnostic);
}
bool prepare_vulkan(bool, ExecutionDiagnostic * diagnostic) noexcept {
    if (diagnostic) {
        *diagnostic = {};
        diagnostic->stage = "VULKAN_DRIVER";
        diagnostic->reason = "BACKEND_NOT_COMPILED";
    }
    return false;
}
ggml_backend_dev_t vulkan_device() noexcept { return nullptr; }
VulkanMemorySnapshot vulkan_memory_snapshot(bool) noexcept { return {}; }
#endif
}
