#pragma once
#include <limits>

namespace subtitle_native {
// An admission estimate never proves Android's limit or a successful native
// allocation. Keep missing data separate from an estimated amount of headroom.
enum class ModelMemoryBudget { UNKNOWN, HEADROOM_SHORT, ESTIMATED_HEADROOM };

inline ModelMemoryBudget model_memory_budget(long long model_bytes, long long available_bytes,
        long long reserve_bytes) noexcept {
    if (model_bytes <= 0 || available_bytes < 0 || reserve_bytes < 0)
        return ModelMemoryBudget::UNKNOWN;
    // Subtract a checked reserve instead of adding it to an untrusted model
    // size: both inputs may already be close to the signed 64-bit boundary.
    const auto headroom = available_bytes >= reserve_bytes ? available_bytes - reserve_bytes : 0;
    return model_bytes > headroom ? ModelMemoryBudget::HEADROOM_SHORT : ModelMemoryBudget::ESTIMATED_HEADROOM;
}

inline long long request_memory_reserve(long long observed_extra_bytes) noexcept {
    constexpr long long floor = 128LL << 20;
    constexpr auto maximum = std::numeric_limits<long long>::max();
    if (observed_extra_bytes <= 0) return floor;
    const auto half = observed_extra_bytes / 2;
    const auto reserve = observed_extra_bytes > maximum - half ? maximum : observed_extra_bytes + half;
    return reserve > floor ? reserve : floor;
}

inline ModelMemoryBudget device_memory_budget(bool observed, bool extension_supported,
        long long required_bytes, long long available_bytes, long long reserve_bytes) noexcept {
    if (!observed || !extension_supported) return ModelMemoryBudget::UNKNOWN;
    return model_memory_budget(required_bytes, available_bytes, reserve_bytes);
}
}
