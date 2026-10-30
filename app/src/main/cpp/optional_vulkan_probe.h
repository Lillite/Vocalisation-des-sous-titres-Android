#pragma once
#include "execution_diagnostic.h"
#include <algorithm>
#include <chrono>
#include <mutex>

namespace subtitle_native {
namespace vulkan_probe {
template<class Api> bool run(Api & api, ExecutionDiagnostic & diagnostic) noexcept {
    try {
        diagnostic.stage = "VULKAN_DRIVER";
        if (!api.load_driver(&diagnostic)) {
            if (!diagnostic.reason) diagnostic.reason = "DRIVER_NOT_ACCESSIBLE";
            return false;
        }
        diagnostic.stage = "VULKAN_DEVICE";
        unsigned int count = 0;
        if (!api.register_backend(&count)) {
            diagnostic.reason = "REGISTRATION_FAILED";
            return false;
        }
        diagnostic.gpu_devices = static_cast<int>(std::min(count, 65'535U));
        if (!count) { diagnostic.reason = "NO_SUPPORTED_GPU"; return false; }
        return true;
    } catch (...) {
        // A backend registry may be partially mutated before an exception.
        // Returning normally seals the failed attempt inside Cache's once flag.
        diagnostic.reason = "UNEXPECTED_FAILURE";
        return false;
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
                output->probe_cached = !fresh;
                output->elapsed_ms = std::min(86'400'000LL, std::max(0LL,
                    static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - start).count())));
            }
            return available_;
        } catch (...) {
            if (output) {
                *output = {};
                output->stage = "VULKAN_DEVICE";
                output->reason = "UNEXPECTED_FAILURE";
            }
            return false;
        }
    }
};
}
}
