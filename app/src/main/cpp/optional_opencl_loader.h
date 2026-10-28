#pragma once
#include "execution_diagnostic.h"

namespace subtitle_native {
// Call only for an explicit AUTO/OPENCL request, while holding ModelLease.
// CPU-only operation never loads or probes a vendor OpenCL implementation.
bool prepare_opencl(ExecutionDiagnostic * diagnostic = nullptr) noexcept;
}
