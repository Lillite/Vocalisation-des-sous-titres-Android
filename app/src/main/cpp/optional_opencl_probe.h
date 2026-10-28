#pragma once
#include "execution_diagnostic.h"
#include <algorithm>
#include <chrono>
#include <mutex>

namespace subtitle_native {
namespace opencl_probe {
inline long long bounded_elapsed(std::chrono::steady_clock::time_point start) noexcept {
    return std::min(86'400'000LL, std::max(0LL, static_cast<long long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count())));
}
inline int bounded_count(unsigned int count) noexcept {
    return static_cast<int>(std::min(count, 65'535U));
}

// The same sequence is exercised with a synthetic driver API in the host test.
// This is a diagnostic seam, not a substitute for a real Android/GPU test.
template<class Api> bool run(Api & api, ExecutionDiagnostic & diagnostic) noexcept {
    const auto failed = [&](const char * reason) { diagnostic.reason = reason; return false; };
    try {
        diagnostic.stage = "OPENCL_DRIVER";
        if (!api.load_driver()) return failed("DRIVER_NOT_ACCESSIBLE");
        if (!api.has_entry_points()) return failed("ENTRY_POINTS_MISSING");
        diagnostic.stage = "OPENCL_DIRECT";
        unsigned int count = 0;
        diagnostic.opencl_status = api.direct_platforms(&count);
        diagnostic.has_opencl_status = true;
        if (diagnostic.opencl_status != 0) return failed("DIRECT_QUERY_FAILED");
        diagnostic.direct_platforms = bounded_count(count);
        if (count == 0) return failed("DIRECT_NO_PLATFORM");
        diagnostic.stage = "OPENCL_ICD";
        diagnostic.has_opencl_status = false;
        // Khronos ICD needs this extension even if the direct vendor query works.
        if (!api.has_icd_entry_point()) return failed("ICD_ENTRY_POINT_MISSING");
        if (!api.configure_icd()) return failed("ICD_CONFIGURATION_FAILED");
        count = 0;
        diagnostic.opencl_status = api.icd_platforms(&count);
        diagnostic.has_opencl_status = true;
        if (diagnostic.opencl_status != 0) return failed("ICD_QUERY_FAILED");
        diagnostic.icd_platforms = bounded_count(count);
        if (count == 0) return failed("ICD_NO_PLATFORM");
        diagnostic.stage = "OPENCL_DEVICE";
        diagnostic.has_opencl_status = false;
        count = 0;
        if (!api.register_backend(&count)) return failed("REGISTRATION_FAILED");
        diagnostic.gpu_devices = bounded_count(count);
        if (count == 0) return failed("NO_SUPPORTED_GPU");
        return true;
    } catch (...) {
        diagnostic.has_opencl_status = false;
        return failed("UNEXPECTED_FAILURE");
    }
}

class Cache {
    std::once_flag attempted_;
    bool available_ = false;
    ExecutionDiagnostic original_;
public:
    template<class Api> bool prepare(Api & api, ExecutionDiagnostic * output) noexcept {
        const auto start = std::chrono::steady_clock::now();
        bool fresh = false;
        try {
            std::call_once(attempted_, [&] {
                fresh = true;
                available_ = run(api, original_);
            });
            if (output) {
                *output = original_;
                output->elapsed_ms = bounded_elapsed(start);
                output->probe_cached = !fresh;
            }
            return available_;
        } catch (...) {
            // std::call_once itself can fail. Do not report a cached driver result.
            if (output) {
                *output = {};
                output->reason = "UNEXPECTED_FAILURE";
                output->elapsed_ms = bounded_elapsed(start);
            }
            return false;
        }
    }
};
}
}
