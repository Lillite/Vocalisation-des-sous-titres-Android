#pragma once
#include "execution_diagnostic.h"
#include "ggml-backend.h"

namespace subtitle_native {
// Call only after an explicit Vulkan request while holding ModelLease. Merely
// linking this helper never probes a driver or registers the Vulkan backend.
bool prepare_vulkan(ExecutionDiagnostic * diagnostic = nullptr) noexcept;
ggml_backend_dev_t vulkan_device() noexcept;
}
