#include "optional_hexagon_backend.h"

#ifdef SUBTITLE_HAS_HEXAGON
#include "optional_hexagon_probe.h"
#include "ggml-hexagon.h"
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
bool private_directory(const std::string & path) noexcept {
    if (path.empty() || path.size() > 32768 || path.front() != '/' ||
        path.find_first_of(";:\\") != std::string::npos || path.find('\0') != std::string::npos)
        return false;
    char * canonical = realpath(path.c_str(), nullptr);
    if (!canonical) return false;
    const bool exact = path == canonical;
    std::free(canonical);
    struct stat info{};
    return exact && lstat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode) &&
        info.st_uid == geteuid() && !(info.st_mode & (S_IWGRP | S_IWOTH));
}
bool private_skeletons(const std::string & path) {
    for (const char * name : {"libggml-htp-v73.so", "libggml-htp-v75.so", "libggml-htp-v79.so",
                              "libggml-htp-v81.so", "libggml-htp-v85.so"}) {
        struct stat info{};
        const std::string file = path + '/' + name;
        if (lstat(file.c_str(), &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != geteuid() ||
            info.st_size < 1 || (info.st_mode & (S_IWUSR | S_IWGRP | S_IWOTH)) || access(file.c_str(), R_OK) != 0)
            return false;
    }
    return true;
}
}
#endif

namespace subtitle_native {
#ifdef SUBTITLE_HAS_HEXAGON
namespace {
hexagon_probe::Cache probe;
std::atomic<ggml_backend_dev_t> selected{nullptr};
struct DriverApi {
    const std::string & path;
    bool load_driver(ExecutionDiagnostic * diagnostic) {
        // FastRPC uses semicolon-separated DSP search paths. Add only fixed
        // firmware locations to the app-owned directory, not inherited paths.
        const std::string dsp_path = path + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp";
        if (setenv("ADSP_LIBRARY_PATH", dsp_path.c_str(), 1) != 0 ||
            setenv("CDSP_LIBRARY_PATH", dsp_path.c_str(), 1) != 0 ||
            setenv("GGML_HEXAGON_DEVICES", "HTP0:0", 1) != 0 ||
            setenv("GGML_HEXAGON_POWER_MODE", "balanced", 1) != 0) {
            diagnostic->reason = "DRIVER_CONFIGURATION_FAILED";
            return false;
        }
        // Merely probing this library never calls FastRPC. Its real entry
        // points are validated by the pinned backend during registration.
        void * driver = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
        if (!driver) { diagnostic->reason = "DRIVER_NOT_ACCESSIBLE"; return false; }
        dlclose(driver);
        return true;
    }
    bool register_backend(unsigned int * count) {
        auto * registration = ggml_backend_hexagon_reg();
        if (!registration) return false;
        ggml_backend_dev_t candidate = nullptr;
        unsigned int supported = 0;
        for (size_t i = 0; i < ggml_backend_reg_dev_count(registration); ++i) {
            auto * device = ggml_backend_reg_dev_get(registration, i);
            if (!device) continue;
            // The pinned backend calls its NPU registry HTP and exposes its
            // devices as GPU for llama.cpp offload scheduling. This is not a
            // claim of GPU execution in the application's public protocol.
            auto * owner = ggml_backend_dev_backend_reg(device);
            if (!owner || std::strcmp(ggml_backend_reg_name(owner), "HTP") != 0) continue;
            if (!candidate) candidate = device;
            if (supported < 65'535U) ++supported;
        }
        *count = supported;
        if (!candidate) return true;
        ggml_backend_register(registration);
        selected.store(candidate, std::memory_order_release);
        return true;
    }
};
}
bool prepare_hexagon(const std::string & library_path, ExecutionDiagnostic * diagnostic) noexcept {
    try {
        if (!private_directory(library_path)) {
            if (diagnostic) { *diagnostic = {}; diagnostic->stage = "HEXAGON_DRIVER"; diagnostic->reason = "LIBRARY_PATH_INVALID"; }
            return false;
        }
        if (!private_skeletons(library_path)) {
            if (diagnostic) { *diagnostic = {}; diagnostic->stage = "HEXAGON_DRIVER"; diagnostic->reason = "SKEL_NOT_ACCESSIBLE"; }
            return false;
        }
        DriverApi driver{library_path};
        return probe.prepare(driver, diagnostic);
    } catch (...) {
        if (diagnostic) { *diagnostic = {}; diagnostic->stage = "HEXAGON_DRIVER"; diagnostic->reason = "UNEXPECTED_FAILURE"; }
        return false;
    }
}
ggml_backend_dev_t hexagon_device() noexcept { return selected.load(std::memory_order_acquire); }
#else
bool prepare_hexagon(const std::string &, ExecutionDiagnostic * diagnostic) noexcept {
    if (diagnostic) {
        *diagnostic = {};
        diagnostic->stage = "HEXAGON_DRIVER";
        diagnostic->reason = "BACKEND_NOT_COMPILED";
    }
    return false;
}
ggml_backend_dev_t hexagon_device() noexcept { return nullptr; }
#endif
}
