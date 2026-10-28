#pragma once

namespace subtitle_native {
// Values are fixed by the application. Never place driver logs, dlerror(),
// model metadata, paths or subtitle text in this protocol.
struct ExecutionDiagnostic {
    const char * stage = "BACKEND";
    const char * reason = nullptr;
    long long elapsed_ms = 0;
    bool has_opencl_status = false;
    int opencl_status = 0;
    int direct_platforms = -1;
    int icd_platforms = -1;
    int gpu_devices = -1;
    bool probe_cached = false;
    bool present() const noexcept { return reason && *reason; }
};
}
