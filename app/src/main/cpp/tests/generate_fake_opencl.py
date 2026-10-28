#!/usr/bin/env python3
"""Generate a host-only, deliberately non-ICD OpenCL C library for ABI tests."""
from pathlib import Path
import argparse
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generate_direct_opencl import declarations, OPTIONAL


def generate(header, output):
    code = ['#define CL_TARGET_OPENCL_VERSION 300', '#define CL_USE_DEPRECATED_OPENCL_1_2_APIS',
            '#define CL_USE_DEPRECATED_OPENCL_1_1_APIS',
            '#include <CL/cl.h>', '#include <cassert>', '#include <cstring>', '#include <atomic>',
            'namespace {', 'std::atomic<unsigned> calls{0}, extension_calls{0};',
            'std::atomic<unsigned long long> last_size{0};',
            'const auto platform_value = reinterpret_cast<cl_platform_id>(0x13579);',
            'const auto device_value = reinterpret_cast<cl_device_id>(0x24680);',
            'const auto context_value = reinterpret_cast<cl_context>(0x35791);',
            'const auto buffer_value = reinterpret_cast<cl_mem>(0x46802);',
            'const auto queue_value = reinterpret_cast<cl_command_queue>(0x57913);',
            'const auto program_value = reinterpret_cast<cl_program>(0x68a24);',
            'const auto kernel_value = reinterpret_cast<cl_kernel>(0x79b35);',
            'const auto event_value = reinterpret_cast<cl_event>(0x8ac46);', '}',
            'extern "C" unsigned direct_test_calls() { return calls.load(); }',
            'extern "C" unsigned direct_test_extension_calls() { return extension_calls.load(); }',
            'extern "C" unsigned long long direct_test_last_size() { return last_size.load(); }',
            'extern "C" CL_API_ENTRY void * CL_API_CALL clGetExtensionFunctionAddress(const char *) { ++extension_calls; return nullptr; }']
    special = {
        'clGetPlatformIDs': 'if (num_platforms) *num_platforms = 1; if (platforms) { assert(num_entries >= 1); platforms[0] = platform_value; } return CL_SUCCESS;',
        'clGetDeviceIDs': 'assert(platform == platform_value && device_type == CL_DEVICE_TYPE_GPU); if (num_devices) *num_devices = 1; if (devices) { assert(num_entries >= 1); devices[0] = device_value; } return CL_SUCCESS;',
        'clGetPlatformInfo': 'assert(platform == platform_value && param_name == CL_PLATFORM_NAME); const char text[] = "Synthetic direct vendor"; if (param_value_size_ret) *param_value_size_ret = sizeof(text); if (param_value) { assert(param_value_size >= sizeof(text)); std::memcpy(param_value, text, sizeof(text)); } return CL_SUCCESS;',
        'clCreateContext': 'assert(num_devices == 1 && devices[0] == device_value); if (pfn_notify) pfn_notify("synthetic callback", "xyz", 3, user_data); if (errcode_ret) *errcode_ret = CL_SUCCESS; return context_value;',
        'clCreateBuffer': 'assert(context == context_value && flags == CL_MEM_READ_WRITE && host_ptr == reinterpret_cast<void *>(0x12345)); last_size.store(size); if (errcode_ret) *errcode_ret = CL_SUCCESS; return buffer_value;',
        'clCreateCommandQueue': 'assert(context == context_value && device == device_value); if (errcode_ret) *errcode_ret = CL_SUCCESS; return queue_value;',
        'clCreateProgramWithSource': 'assert(context == context_value && count == 1 && strings && std::strcmp(strings[0], "source") == 0); if (errcode_ret) *errcode_ret = CL_SUCCESS; return program_value;',
        'clBuildProgram': 'assert(program == program_value); if (pfn_notify) pfn_notify(program, user_data); return CL_SUCCESS;',
        'clCreateKernel': 'assert(program == program_value && std::strcmp(kernel_name, "kernel") == 0); if (errcode_ret) *errcode_ret = CL_SUCCESS; return kernel_value;',
        'clSetKernelArg': 'assert(kernel == kernel_value && arg_index == 7 && arg_size == sizeof(cl_mem) && *static_cast<const cl_mem *>(arg_value) == buffer_value); return CL_SUCCESS;',
        'clEnqueueNDRangeKernel': 'assert(command_queue == queue_value && kernel == kernel_value && work_dim == 3 && global_work_offset[0] == 9 && global_work_size[0] == (size_t(1) << 36) && local_work_size[0] == 1 && num_events_in_wait_list == 1 && event_wait_list[0] == event_value); if (event) *event = event_value; return CL_SUCCESS;',
        'clWaitForEvents': 'assert(num_events == 1 && event_list[0] == event_value); return CL_SUCCESS;',
    }
    for name, (ret, args, names) in declarations(header).items():
        if name in OPTIONAL:
            code.append('#ifdef DIRECT_TEST_OPTIONAL')
        if name == 'clSetKernelArg':
            code.append('#ifndef DIRECT_TEST_MISSING_REQUIRED')
        code += ['extern "C" CL_API_ENTRY ' + ret + ' CL_API_CALL ' + name + '(' + args + ') {', '    ++calls;']
        if name in special:
            code.append('    ' + special[name].replace('; if (', ';\n    if (').replace('; return ', ';\n    return '))
        elif ret == 'cl_int':
            code.append('    return CL_INVALID_VALUE;' if name in OPTIONAL else '    return CL_SUCCESS;')
        else:
            if 'errcode_ret' in names:
                code.append('    if (errcode_ret) *errcode_ret = CL_INVALID_VALUE;')
            code.append('    return nullptr;')
        code.append('}')
        if name in OPTIONAL or name == 'clSetKernelArg':
            code.append('#endif')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(code) + '\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('header', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    generate(a.header.read_text(), a.output)
