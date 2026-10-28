#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#define CL_USE_DEPRECATED_OPENCL_1_1_APIS
#include <CL/cl.h>
#include "direct_opencl_dispatch.h"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <cstring>
#include <dlfcn.h>
#include <atomic>
#include <thread>
#include <vector>
#include <iostream>

namespace {
void CL_CALLBACK context_callback(const char * text, const void * bytes, size_t size, void * user) {
    assert(std::strcmp(text, "synthetic callback") == 0 && size == 3 && std::memcmp(bytes, "xyz", 3) == 0);
    ++*static_cast<int *>(user);
}
void CL_CALLBACK program_callback(cl_program program, void * user) {
    assert(program == reinterpret_cast<cl_program>(0x68a24));
    ++*static_cast<int *>(user);
}
}
int main(int argc, char ** argv) {
    assert(argc == 3);
    const bool missing = std::strcmp(argv[2], "missing") == 0;
    const bool optional = std::strcmp(argv[2], "optional") == 0;
    // The actual forwarding symbols fail safely before preparation and cannot
    // load a vendor library merely because a CPU-only process contains them.
    assert(!subtitle_native::direct_opencl::ready());
    cl_uint count = 99;
    assert(clGetPlatformIDs(0, nullptr, &count) == CL_INVALID_OPERATION && count == 0);
    assert(dlopen(argv[1], RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD) == nullptr);
    void * driver = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(driver);
    auto calls = reinterpret_cast<unsigned (*)()>(dlsym(driver, "direct_test_calls"));
    auto ext_calls = reinterpret_cast<unsigned (*)()>(dlsym(driver, "direct_test_extension_calls"));
    auto size = reinterpret_cast<unsigned long long (*)()>(dlsym(driver, "direct_test_last_size"));
    auto extension = reinterpret_cast<decltype(&clGetExtensionFunctionAddress)>(dlsym(driver, "clGetExtensionFunctionAddress"));
    assert(calls && ext_calls && size && extension);
    assert(extension("clIcdGetPlatformIDsKHR") == nullptr);
    assert(ext_calls() == 1); // Only this explicit test query; never the dispatcher.
    if (missing) {
        assert(!subtitle_native::direct_opencl::bind(driver));
        assert(subtitle_native::direct_opencl::missing_symbol() && std::strcmp(subtitle_native::direct_opencl::missing_symbol(), "clSetKernelArg") == 0);
        assert(!subtitle_native::direct_opencl::ready());
        count = 99;
        assert(clGetPlatformIDs(0, nullptr, &count) == CL_INVALID_OPERATION && count == 0);
        assert(calls() == 0 && ext_calls() == 1);
        std::cout << "Missing required entry point leaves no partially published dispatch table.\n";
        return 0;
    }
    // Real wrappers may be called concurrently with publication. Only a complete
    // immutable table or a clean unavailable result may be observed.
    std::atomic<bool> start{false};
    std::vector<std::thread> workers;
    for (int i = 0; i < 8; ++i) workers.emplace_back([&] {
        while (!start.load()) std::this_thread::yield();
        for (int n = 0; n < 1000; ++n) {
            cl_uint value = 99;
            const auto status = clGetPlatformIDs(0, nullptr, &value);
            assert((status == CL_INVALID_OPERATION && value == 0) || (status == CL_SUCCESS && value == 1));
        }
        assert(subtitle_native::direct_opencl::bind(driver));
    });
    start.store(true);
    assert(subtitle_native::direct_opencl::bind(driver));
    for (auto & thread : workers) thread.join();
    assert(subtitle_native::direct_opencl::ready());
    assert(subtitle_native::direct_opencl::missing_symbol() == nullptr);
    cl_platform_id platform = nullptr;
    assert(clGetPlatformIDs(1, &platform, &count) == CL_SUCCESS && count == 1 && platform == reinterpret_cast<cl_platform_id>(0x13579));
    cl_device_id device = nullptr;
    assert(clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 1, &device, &count) == CL_SUCCESS && count == 1 && device == reinterpret_cast<cl_device_id>(0x24680));
    char name[64]{};
    assert(clGetPlatformInfo(platform, CL_PLATFORM_NAME, sizeof(name), name, nullptr) == CL_SUCCESS && std::strcmp(name, "Synthetic direct vendor") == 0);
    int callbacks = 0;
    cl_int error = 99;
    const auto context = clCreateContext(nullptr, 1, &device, context_callback, &callbacks, &error);
    assert(context == reinterpret_cast<cl_context>(0x35791) && error == CL_SUCCESS && callbacks == 1);
    const size_t large = (size_t(1) << 40) + 7;
    const auto buffer = clCreateBuffer(context, CL_MEM_READ_WRITE, large, reinterpret_cast<void *>(0x12345), &error);
    assert(buffer == reinterpret_cast<cl_mem>(0x46802) && error == CL_SUCCESS && size() == large);
    const auto queue = clCreateCommandQueue(context, device, 0, &error);
    assert(queue == reinterpret_cast<cl_command_queue>(0x57913) && error == CL_SUCCESS);
    const char * source = "source";
    const auto program = clCreateProgramWithSource(context, 1, &source, nullptr, &error);
    assert(program == reinterpret_cast<cl_program>(0x68a24) && error == CL_SUCCESS);
    assert(clBuildProgram(program, 0, nullptr, "", program_callback, &callbacks) == CL_SUCCESS && callbacks == 2);
    const auto kernel = clCreateKernel(program, "kernel", &error);
    assert(kernel == reinterpret_cast<cl_kernel>(0x79b35) && error == CL_SUCCESS);
    assert(clSetKernelArg(kernel, 7, sizeof(buffer), &buffer) == CL_SUCCESS);
    const size_t offset[] = {9, 0, 0}, global[] = {size_t(1) << 36, 1, 1}, local[] = {1, 1, 1};
    const auto before = reinterpret_cast<cl_event>(0x8ac46);
    cl_event after = nullptr;
    assert(clEnqueueNDRangeKernel(queue, kernel, 3, offset, global, local, 1, &before, &after) == CL_SUCCESS && after == before);
    assert(clWaitForEvents(1, &after) == CL_SUCCESS);
    error = 99;
    assert(clCreateBufferWithProperties(context, nullptr, CL_MEM_READ_WRITE, 1, nullptr, &error) == nullptr);
    assert(error == (optional ? CL_INVALID_VALUE : CL_INVALID_OPERATION));
    assert(clGetKernelSubGroupInfo(kernel, device, 0, 0, nullptr, 0, nullptr, nullptr) == (optional ? CL_INVALID_VALUE : CL_INVALID_OPERATION));
    assert(clGetEventProfilingInfo(after, 0, 0, nullptr, nullptr) == (optional ? CL_INVALID_VALUE : CL_PROFILING_INFO_NOT_AVAILABLE));
    assert(ext_calls() == 1);
    // No dlclose: vendor pointers and callbacks intentionally live to process exit.
    std::cout << "Actual direct vendor forwarding passed: non-ICD opaque handles, callbacks, 64-bit sizes, concurrent publication, optional APIs and CPU-only isolation (synthetic host driver; no GPU execution).\n";
}
