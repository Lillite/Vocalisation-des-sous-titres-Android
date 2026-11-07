#pragma once
#include "execution_diagnostic.h"
#include "ggml-backend.h"
#include "vulkan_memory_telemetry.h"

namespace subtitle_native {
// Call only after an explicit Vulkan request while holding ModelLease. Merely
// linking this helper never probes a driver or registers the Vulkan backend.
bool prepare_vulkan(ExecutionDiagnostic * diagnostic = nullptr) noexcept;
// A changed profile is refused once the registry has been attempted. Recreate
// the private model process to build genuinely different device/pipeline state.
bool prepare_vulkan(bool qualcomm_compatibility, ExecutionDiagnostic * diagnostic) noexcept;
ggml_backend_dev_t vulkan_device() noexcept;
VulkanMemorySnapshot vulkan_memory_snapshot(bool refresh_budget = true) noexcept;
}
