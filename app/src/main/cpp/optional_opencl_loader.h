#pragma once

namespace subtitle_native {
// Call only for an explicit AUTO/OPENCL request, while holding ModelLease.
// CPU-only operation never loads or probes a vendor OpenCL implementation.
bool prepare_opencl() noexcept;
const char * opencl_unavailable_reason() noexcept;
}
