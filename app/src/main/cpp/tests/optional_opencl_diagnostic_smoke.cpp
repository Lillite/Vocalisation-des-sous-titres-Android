#include "optional_opencl_probe.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
struct SyntheticApi {
    bool driver = true, entry_points = true, binding = true, icd_entry = true, configuration = true, registration = true;
    int direct_status = 0, icd_status = 0;
    unsigned int direct_count = 1, icd_count = 1, gpu_count = 1;
    int loads = 0, direct_calls = 0, icd_calls = 0, registrations = 0, bindings = 0;
    bool throw_during_icd = false;
    bool load_driver() { ++loads; return driver; }
    bool has_entry_points() { return entry_points; }
    int direct_platforms(unsigned int * count) { ++direct_calls; *count = direct_count; return direct_status; }
    bool bind_direct_api() { ++bindings; return binding; }
    const char * missing_symbol() { return binding ? nullptr : "clSetKernelArg"; }
    bool has_icd_entry_point() { return icd_entry; }
    bool configure_icd() { return configuration; }
    int icd_platforms(unsigned int * count) {
        ++icd_calls;
        if (throw_during_icd) throw std::runtime_error("synthetic private information must not be emitted");
        *count = icd_count; return icd_status;
    }
    bool register_backend(unsigned int * count) { ++registrations; *count = gpu_count; return registration; }
};
void expect_failure(SyntheticApi & api, const char * stage, const char * reason,
        int direct_count, int icd_count, int gpu_count, bool has_status = false, int status = 0) {
    subtitle_native::opencl_probe::Cache cache;
    subtitle_native::ExecutionDiagnostic first, repeated;
    assert(!cache.prepare(api, &first));
    assert(first.present() && std::strcmp(first.stage, stage) == 0 && std::strcmp(first.reason, reason) == 0);
    assert(first.direct_platforms == direct_count && first.icd_platforms == icd_count && first.gpu_devices == gpu_count);
    assert(first.has_opencl_status == has_status && (!has_status || first.opencl_status == status));
    if (std::strcmp(reason, "DIRECT_SYMBOLS_MISSING") == 0) assert(first.missing_symbol && std::strcmp(first.missing_symbol, "clSetKernelArg") == 0);
    else assert(first.missing_symbol == nullptr);
    assert(first.elapsed_ms >= 0 && first.elapsed_ms <= 86'400'000 && !first.probe_cached);
    const auto original_calls = api.loads + api.direct_calls + api.icd_calls + api.registrations + api.bindings;
    // A later call is an explicit cached failure, never a new GPU measurement.
    assert(!cache.prepare(api, &repeated) && repeated.probe_cached);
    assert(original_calls == api.loads + api.direct_calls + api.icd_calls + api.registrations + api.bindings);
    assert(std::strcmp(first.stage, repeated.stage) == 0 && std::strcmp(first.reason, repeated.reason) == 0);
    assert(first.direct_platforms == repeated.direct_platforms && first.icd_platforms == repeated.icd_platforms);
    assert(first.missing_symbol == repeated.missing_symbol);
    assert(first.gpu_devices == repeated.gpu_devices && first.has_opencl_status == repeated.has_opencl_status);
    assert(!first.has_opencl_status || first.opencl_status == repeated.opencl_status);
}
}
int main() {
    { SyntheticApi api; api.driver = false;
      expect_failure(api, "OPENCL_DRIVER", "DRIVER_NOT_ACCESSIBLE", -1, -1, -1); assert(api.direct_calls == 0); }
    { SyntheticApi api; api.entry_points = false;
      expect_failure(api, "OPENCL_DRIVER", "ENTRY_POINTS_MISSING", -1, -1, -1); assert(api.direct_calls == 0); }
    { SyntheticApi api; api.direct_status = -1001;
      expect_failure(api, "OPENCL_DIRECT", "DIRECT_QUERY_FAILED", -1, -1, -1, true, -1001); assert(api.icd_calls == 0); }
    { SyntheticApi api; api.direct_count = 0;
      expect_failure(api, "OPENCL_DIRECT", "DIRECT_NO_PLATFORM", 0, -1, -1, true, 0); assert(api.icd_calls == 0); }
    { SyntheticApi api; api.binding = false;
      expect_failure(api, "OPENCL_DIRECT", "DIRECT_SYMBOLS_MISSING", 1, -1, -1); assert(api.registrations == 0); }
    // Each former ICD rejection is now irrelevant: the actual vendor C API
    // surface, not an optional multi-vendor extension, decides availability.
    for (int disabled = 0; disabled < 5; ++disabled) {
        SyntheticApi api;
        if (disabled == 0) api.icd_entry = false;
        if (disabled == 1) api.configuration = false;
        if (disabled == 2) api.icd_status = -1001;
        if (disabled == 3) api.icd_count = 0;
        if (disabled == 4) api.throw_during_icd = true;
        subtitle_native::opencl_probe::Cache cache;
        subtitle_native::ExecutionDiagnostic value;
        assert(cache.prepare(api, &value) && !value.present());
        assert(api.icd_calls == 0 && api.bindings == 1 && value.icd_platforms == -1);
    }
    { SyntheticApi api; api.registration = false;
      expect_failure(api, "OPENCL_DEVICE", "REGISTRATION_FAILED", 1, -1, -1); }
    { SyntheticApi api; api.gpu_count = 0;
      expect_failure(api, "OPENCL_DEVICE", "NO_SUPPORTED_GPU", 1, -1, 0); }
    {
        SyntheticApi api;
        api.direct_count = 100'000; api.icd_count = 200'000; api.gpu_count = 300'000;
        subtitle_native::opencl_probe::Cache cache;
        subtitle_native::ExecutionDiagnostic value;
        assert(cache.prepare(api, &value) && !value.present() && !value.probe_cached);
        assert(value.direct_platforms == 65'535 && value.icd_platforms == -1 && value.gpu_devices == 65'535);
        assert(cache.prepare(api, &value) && !value.present() && value.probe_cached);
        assert(api.loads == 1 && api.registrations == 1);
    }
    {
        SyntheticApi api;
        subtitle_native::opencl_probe::Cache cache;
        std::vector<subtitle_native::ExecutionDiagnostic> reports(8);
        std::vector<std::thread> callers;
        for (auto & report : reports) callers.emplace_back([&cache, &api, &report] { assert(cache.prepare(api, &report)); });
        for (auto & caller : callers) caller.join();
        int fresh = 0;
        for (const auto & report : reports) { assert(!report.present()); if (!report.probe_cached) ++fresh; }
        assert(fresh == 1 && api.loads == 1 && api.direct_calls == 1 && api.icd_calls == 0 && api.bindings == 1 && api.registrations == 1);
    }
    std::cout << "Bounded OpenCL diagnostics: direct vendor path without ICD dependency, failure stages, status, counters, cached failures, success and concurrent probe passed (synthetic host API; no GPU execution).\n";
}
