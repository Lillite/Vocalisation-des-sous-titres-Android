#pragma once
#include <utility>

namespace subtitle_native {
// Protects a partially initialized Vulkan buffer before its existing shared
// owner becomes responsible. The cleanup callback also resets that owner, so a
// thrown map/bind/address operation cannot leak or free the same resource twice.
template<class Cleanup> class VulkanBufferTransaction {
    Cleanup cleanup_;
    bool committed_ = false;
public:
    explicit VulkanBufferTransaction(Cleanup cleanup) : cleanup_(std::move(cleanup)) { }
    VulkanBufferTransaction(const VulkanBufferTransaction &) = delete;
    VulkanBufferTransaction & operator=(const VulkanBufferTransaction &) = delete;
    ~VulkanBufferTransaction() noexcept { if (!committed_) cleanup_(); }
    void commit() noexcept { committed_ = true; }
};
}
