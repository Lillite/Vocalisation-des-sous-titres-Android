#pragma once
#include <mutex>

namespace subtitle_native {
// Device/pipeline state in the pinned Vulkan registry is process-global. This
// tiny gate refuses relabelling old pipelines as a newly requested profile.
class VulkanProfileLatch {
    std::mutex mutex_;
    bool latched_ = false;
    bool compatibility_ = false;
public:
    template<class Configure> bool accept(bool requested, Configure configure) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (latched_) return requested == compatibility_;
        if (!configure(requested)) return false;
        compatibility_ = requested;
        latched_ = true;
        return true;
    }
};
}
