#pragma once

namespace subtitle_native::direct_opencl {
// The caller owns and keeps the Android vendor handle alive until process exit.
// Resolves only the reviewed C API surface; never calls an ICD extension.
// An incomplete table is never published. A published table is immutable.
bool bind(void * vendor) noexcept;
bool ready() noexcept;
// Null or a literal from the reviewed API table; never loader-provided text.
const char * missing_symbol() noexcept;
}
