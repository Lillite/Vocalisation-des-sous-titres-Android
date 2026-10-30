#pragma once
#include "execution_diagnostic.h"

namespace subtitle_native {
// Bind only the four Vulkan loader entry points directly referenced by the
// pinned backend. The driver's remaining calls use Vulkan-Hpp's dispatch table.
bool bind_vulkan_driver(ExecutionDiagnostic * diagnostic) noexcept;
#ifdef SUBTITLE_VULKAN_DISPATCH_TESTING
// Host-only seam for the real forwarding regression; absent from Android.
bool bind_vulkan_test_driver(const char * path, ExecutionDiagnostic * diagnostic) noexcept;
#endif
}
