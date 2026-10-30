#include "optional_vulkan_dispatch.h"
#include <vulkan/vulkan_core.h>
#include <atomic>
#include <cstdlib>
#include <dlfcn.h>
#include <mutex>

namespace {
struct Dispatch {
    PFN_vkGetInstanceProcAddr get_instance = nullptr;
    PFN_vkGetDeviceProcAddr get_device = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 get_features = nullptr;
    PFN_vkCmdCopyBuffer copy_buffer = nullptr;
};
Dispatch table;
std::atomic<const Dispatch *> published{nullptr};
std::once_flag attempted;
const char * failure = "DRIVER_NOT_ACCESSIBLE";
void * driver = nullptr;
const Dispatch & bound() {
    const auto * value = published.load(std::memory_order_acquire);
    // A call before explicit preparation is a programming error. Never silently
    // pretend that an unbound copy or feature-query operation was executed.
    if (!value) std::abort();
    return *value;
}
}
namespace subtitle_native {
namespace {
bool bind_driver(const char * path, ExecutionDiagnostic * diagnostic) noexcept {
    try {
        std::call_once(attempted, [path] {
            driver = dlopen(path, RTLD_NOW | RTLD_LOCAL);
            if (!driver) return;
            table.get_instance = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(driver, "vkGetInstanceProcAddr"));
            table.get_device = reinterpret_cast<PFN_vkGetDeviceProcAddr>(dlsym(driver, "vkGetDeviceProcAddr"));
            table.get_features = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(dlsym(driver, "vkGetPhysicalDeviceFeatures2"));
            table.copy_buffer = reinterpret_cast<PFN_vkCmdCopyBuffer>(dlsym(driver, "vkCmdCopyBuffer"));
            if (!table.get_instance || !table.get_device || !table.get_features || !table.copy_buffer) {
                failure = "ENTRY_POINTS_MISSING";
                return;
            }
            // Keep the Android loader open for the life of the process. Its
            // function pointers can outlive an individual translation session.
            published.store(&table, std::memory_order_release);
        });
        if (published.load(std::memory_order_acquire)) return true;
        if (diagnostic) diagnostic->reason = failure;
    } catch (...) {
        if (diagnostic) diagnostic->reason = "UNEXPECTED_FAILURE";
    }
    return false;
}
}
bool bind_vulkan_driver(ExecutionDiagnostic * diagnostic) noexcept {
    return bind_driver("libvulkan.so", diagnostic);
}
#ifdef SUBTITLE_VULKAN_DISPATCH_TESTING
bool bind_vulkan_test_driver(const char * path, ExecutionDiagnostic * diagnostic) noexcept {
    return bind_driver(path, diagnostic);
}
#endif
}
extern "C" {
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char * name) {
    return bound().get_instance(instance, name);
}
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char * name) {
    return bound().get_device(device, name);
}
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice device, VkPhysicalDeviceFeatures2 * features) {
    bound().get_features(device, features);
}
VKAPI_ATTR void VKAPI_CALL vkCmdCopyBuffer(VkCommandBuffer command, VkBuffer source, VkBuffer destination,
    uint32_t count, const VkBufferCopy * regions) {
    bound().copy_buffer(command, source, destination, count, regions);
}
}
