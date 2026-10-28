#include "optional_opencl_loader.h"

#ifdef SUBTITLE_HAS_OPENCL
#include "optional_opencl_probe.h"
#include "direct_opencl_dispatch.h"
#include "ggml-backend.h"
#include "ggml-opencl.h"
#include <CL/cl.h>
#include <dlfcn.h>
#endif

namespace subtitle_native {
#ifdef SUBTITLE_HAS_OPENCL
namespace {
opencl_probe::Cache probe;
struct DriverApi {
    // Keep the Android-provided library loaded until process exit. Vendor calls
    // pointers may outlive a model session. No vendor library is bundled.
    void * vendor = nullptr;
    decltype(&clGetPlatformIDs) direct = nullptr;
    bool load_driver() {
        vendor = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
        return vendor != nullptr;
    }
    bool has_entry_points() {
        direct = reinterpret_cast<decltype(direct)>(dlsym(vendor, "clGetPlatformIDs"));
        return direct != nullptr;
    }
    int direct_platforms(unsigned int * count) { return direct(0, nullptr, count); }
    bool bind_direct_api() { return direct_opencl::bind(vendor); }
    const char * missing_symbol() { return direct_opencl::missing_symbol(); }
    bool register_backend(unsigned int * count) {
        auto * registration = ggml_backend_opencl_reg();
        if (!registration) return false;
        unsigned int supported = 0;
        for (size_t i = 0; i < ggml_backend_reg_dev_count(registration); ++i) {
            auto * device = ggml_backend_reg_dev_get(registration, i);
            if (!device) continue;
            const auto type = ggml_backend_dev_type(device);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                if (supported < 65'535U) ++supported;
            }
        }
        *count = supported;
        if (supported == 0) return true;
        // CPU-only initialization excludes OpenCL. Register explicitly only
        // after the Android vendor's direct C entry points have been checked.
        ggml_backend_register(registration);
        return true;
    }
};
DriverApi driver;
}
bool prepare_opencl(ExecutionDiagnostic * diagnostic) noexcept {
    return probe.prepare(driver, diagnostic);
}
#else
bool prepare_opencl(ExecutionDiagnostic * diagnostic) noexcept {
    if (diagnostic) {
        *diagnostic = {};
        diagnostic->reason = "BACKEND_NOT_COMPILED";
    }
    return false;
}
#endif
}
