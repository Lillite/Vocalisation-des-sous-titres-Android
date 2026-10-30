#include "optional_vulkan_backend.h"

#ifdef SUBTITLE_HAS_VULKAN
#include "optional_vulkan_dispatch.h"
#include "optional_vulkan_probe.h"
#include "ggml-vulkan.h"
#include <atomic>
#endif

namespace subtitle_native {
#ifdef SUBTITLE_HAS_VULKAN
namespace {
vulkan_probe::Cache probe;
std::atomic<ggml_backend_dev_t> selected{nullptr};
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
                if (!candidate) candidate = device;
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
    return probe.prepare(driver, diagnostic);
}
ggml_backend_dev_t vulkan_device() noexcept { return selected.load(std::memory_order_acquire); }
#else
bool prepare_vulkan(ExecutionDiagnostic * diagnostic) noexcept {
    if (diagnostic) {
        *diagnostic = {};
        diagnostic->stage = "VULKAN_DRIVER";
        diagnostic->reason = "BACKEND_NOT_COMPILED";
    }
    return false;
}
ggml_backend_dev_t vulkan_device() noexcept { return nullptr; }
#endif
}
