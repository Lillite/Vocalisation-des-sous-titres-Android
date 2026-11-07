#include "optional_hexagon_probe.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <atomic>
#include <cassert>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

struct SyntheticApi {
    bool available = true, registered = true, throwing = false;
    unsigned int devices = 1;
    int loads = 0, registrations = 0;
    bool load_driver(subtitle_native::ExecutionDiagnostic * d) {
        ++loads;
        if (!available) d->reason = "DRIVER_NOT_ACCESSIBLE";
        return available;
    }
    bool register_backend(unsigned int * count) {
        ++registrations;
        if (throwing) throw std::runtime_error("private driver details must not be emitted");
        *count = devices;
        return registered;
    }
};
int main() {
    using subtitle_native::hexagon_probe::Cache;
    subtitle_native::ExecutionDiagnostic d;
    {
        SyntheticApi missing; missing.available = false;
        Cache cache;
        assert(!cache.prepare(missing, &d) && missing.loads == 1 && missing.registrations == 0);
        assert(std::strcmp(d.stage, "HEXAGON_DRIVER") == 0 && std::strcmp(d.reason, "DRIVER_NOT_ACCESSIBLE") == 0);
        assert(!cache.prepare(missing, &d) && d.probe_cached && missing.loads == 1);
    }
    {
        SyntheticApi absent; absent.devices = 0;
        Cache cache;
        assert(!cache.prepare(absent, &d) && std::strcmp(d.reason, "NO_SUPPORTED_NPU") == 0);
        assert(d.gpu_devices == -1); // An NPU count must not become a GPU claim.
    }
    {
        SyntheticApi unregistered; unregistered.registered = false;
        Cache cache;
        assert(!cache.prepare(unregistered, &d) && std::strcmp(d.reason, "REGISTRATION_FAILED") == 0);
    }
    {
        SyntheticApi partial; partial.throwing = true;
        Cache cache;
        assert(!cache.prepare(partial, &d) && std::strcmp(d.reason, "UNEXPECTED_FAILURE") == 0);
        partial.throwing = false;
        assert(!cache.prepare(partial, &d) && d.probe_cached && partial.registrations == 1);
    }
    {
        SyntheticApi good;
        Cache cache;
        std::vector<std::thread> threads;
        std::atomic<int> ready{0}, fresh{0};
        for (int i = 0; i < 16; ++i) threads.emplace_back([&] {
            subtitle_native::ExecutionDiagnostic own;
            assert(cache.prepare(good, &own) && !own.present());
            assert(own.gpu_devices == -1 && own.elapsed_ms >= 0 && own.elapsed_ms <= 86'400'000);
            ++ready;
            if (!own.probe_cached) ++fresh;
        });
        for (auto & thread : threads) thread.join();
        assert(ready == 16 && fresh == 1 && good.loads == 1 && good.registrations == 1);
    }
    std::cout << "Hexagon probe: absent driver, no NPU, registration failure, sealed partial failure and concurrent one-time probe passed (synthetic host API, no NPU executed).\n";
}
