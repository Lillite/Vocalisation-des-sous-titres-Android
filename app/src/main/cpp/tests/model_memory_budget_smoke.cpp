#include "model_memory_budget.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <iostream>
#include <limits>

int main() {
    using subtitle_native::ModelMemoryBudget;
    using subtitle_native::model_memory_budget;
    constexpr long long reserve = 1LL << 30;
    constexpr auto maximum = std::numeric_limits<long long>::max();
    assert(model_memory_budget(0, reserve, reserve) == ModelMemoryBudget::UNKNOWN);
    assert(model_memory_budget(-1, reserve, reserve) == ModelMemoryBudget::UNKNOWN);
    assert(model_memory_budget(1, -1, reserve) == ModelMemoryBudget::UNKNOWN);
    assert(model_memory_budget(1, reserve, -1) == ModelMemoryBudget::UNKNOWN);
    assert(model_memory_budget(1, 0, reserve) == ModelMemoryBudget::HEADROOM_SHORT);
    assert(model_memory_budget(1, reserve - 1, reserve) == ModelMemoryBudget::HEADROOM_SHORT);
    assert(model_memory_budget(1, reserve, reserve) == ModelMemoryBudget::HEADROOM_SHORT);
    assert(model_memory_budget(1, reserve + 1, reserve) == ModelMemoryBudget::ESTIMATED_HEADROOM);
    assert(model_memory_budget(reserve, 2 * reserve - 1, reserve) == ModelMemoryBudget::HEADROOM_SHORT);
    assert(model_memory_budget(reserve, 2 * reserve, reserve) == ModelMemoryBudget::ESTIMATED_HEADROOM);
    assert(model_memory_budget(maximum, maximum, reserve) == ModelMemoryBudget::HEADROOM_SHORT);
    assert(model_memory_budget(maximum - reserve, maximum, reserve) == ModelMemoryBudget::ESTIMATED_HEADROOM);
    assert(model_memory_budget(maximum, maximum, 0) == ModelMemoryBudget::ESTIMATED_HEADROOM);
    assert(model_memory_budget(1, maximum - 1, maximum) == ModelMemoryBudget::HEADROOM_SHORT);
    using subtitle_native::request_memory_reserve;
    assert(request_memory_reserve(-1) == (128LL << 20));
    assert(request_memory_reserve(0) == (128LL << 20));
    assert(request_memory_reserve(128LL << 20) == (192LL << 20));
    assert(request_memory_reserve(maximum) == maximum);
    assert(request_memory_reserve(maximum - 1) == maximum);
    using subtitle_native::device_memory_budget;
    assert(device_memory_budget(false, true, reserve, 0, 0) == ModelMemoryBudget::UNKNOWN);
    assert(device_memory_budget(true, false, reserve, 0, 0) == ModelMemoryBudget::UNKNOWN);
    assert(device_memory_budget(false, false, reserve, maximum, 0) == ModelMemoryBudget::UNKNOWN);
    assert(device_memory_budget(true, true, reserve, reserve - 1, 0) == ModelMemoryBudget::HEADROOM_SHORT);
    assert(device_memory_budget(true, true, reserve, reserve, 0) == ModelMemoryBudget::ESTIMATED_HEADROOM);
    assert(device_memory_budget(true, true, maximum, maximum, reserve) == ModelMemoryBudget::HEADROOM_SHORT);
    std::cout << "Model admission estimate: missing samples, reserve boundaries, exact equality and signed 64-bit overflow boundaries passed.\n";
}
