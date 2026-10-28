#include "optional_opencl_loader.h"

#ifdef SUBTITLE_HAS_OPENCL
#include "ggml-backend.h"
#include "ggml-opencl.h"
#include <CL/cl.h>
#include <dlfcn.h>
#include <cstdlib>
#include <mutex>
#endif

namespace subtitle_native {
#ifdef SUBTITLE_HAS_OPENCL
namespace {
std::once_flag attempted;
bool available = false;
const char * unavailable = "OpenCL vendor driver not prepared";
// Keep the vendor loaded until process exit; ICD dispatch pointers may outlive a
// model session. This is not an application-supplied or downloaded library.
void * vendor = nullptr;
}
bool prepare_opencl() noexcept {
    try {
        std::call_once(attempted, [] {
            vendor = dlopen("libOpenCL.so", RTLD_NOW | RTLD_LOCAL);
            if (!vendor) { unavailable = "OpenCL vendor library unavailable"; return; }
            const auto platforms = reinterpret_cast<decltype(&clGetPlatformIDs)>(dlsym(vendor, "clGetPlatformIDs"));
            const auto extension = dlsym(vendor, "clGetExtensionFunctionAddress");
            if (!platforms || !extension) { unavailable = "OpenCL vendor ICD entry points unavailable"; return; }
            cl_uint count = 0;
            if (platforms(0, nullptr, &count) != CL_SUCCESS || count == 0) {
                unavailable = "OpenCL vendor exposes no platform"; return;
            }
            // The statically linked Khronos loader has no DT_NEEDED dependency on
            // libOpenCL.so. Give it the optional Android-public vendor library,
            // rather than assuming Linux ICD files exist on an Android phone.
            if (setenv("OCL_ICD_FILENAMES", "libOpenCL.so", 1) != 0) {
                unavailable = "OpenCL ICD configuration unavailable"; return;
            }
            auto * registration = ggml_backend_opencl_reg();
            if (!registration) { unavailable = "OpenCL backend registration unavailable"; return; }
            bool gpu = false;
            for (size_t i = 0; i < ggml_backend_reg_dev_count(registration); ++i) {
                auto * device = ggml_backend_reg_dev_get(registration, i);
                if (device && ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_GPU) gpu = true;
            }
            if (!gpu) { unavailable = "OpenCL exposes no supported GPU device"; return; }
            // Automatic registry initialization deliberately excludes OpenCL. A
            // previous CPU request therefore cannot cache an empty GPU inventory,
            // and strict CPU fallback after a driver crash never reopens it.
            ggml_backend_register(registration);
            available = true; unavailable = "";
        });
    } catch (...) { return false; }
    return available;
}
const char * opencl_unavailable_reason() noexcept { return unavailable; }
#else
bool prepare_opencl() noexcept { return false; }
const char * opencl_unavailable_reason() noexcept { return "OpenCL backend not compiled for this ABI"; }
#endif
}
