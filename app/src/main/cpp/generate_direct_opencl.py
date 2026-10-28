#!/usr/bin/env python3
"""Generate ABI-exact forwarding definitions from the pinned Khronos C header.

The allowlist is the reviewed API surface of the pinned ggml OpenCL backend and
program cache. New upstream calls require review rather than implicit exposure.
No vendor library, function address or code is downloaded by the application.
"""
from pathlib import Path
import argparse
import re

FUNCTIONS = '''clBuildProgram clCreateBuffer clCreateBufferWithProperties
clCreateCommandQueue clCreateContext clCreateImage clCreateKernel
clCreateProgramWithBinary clCreateProgramWithSource clCreateSubBuffer
clEnqueueBarrierWithWaitList clEnqueueCopyBuffer clEnqueueFillBuffer
clEnqueueMapBuffer clEnqueueMarkerWithWaitList clEnqueueNDRangeKernel
clEnqueueReadBuffer clEnqueueUnmapMemObject clEnqueueWriteBuffer clFinish clFlush
clGetDeviceIDs clGetDeviceInfo clGetEventProfilingInfo clGetKernelInfo
clGetKernelSubGroupInfo clGetKernelWorkGroupInfo clGetPlatformIDs clGetPlatformInfo
clGetProgramBuildInfo clGetProgramInfo clReleaseEvent clReleaseKernel
clReleaseMemObject clReleaseProgram clSetKernelArg clWaitForEvents'''.split()
# Upstream already handles these missing capabilities. Profiling is disabled in
# this application's build. Program-cache failures fall back to source compilation.
OPTIONAL = {'clCreateBufferWithProperties', 'clGetKernelSubGroupInfo',
            'clGetEventProfilingInfo', 'clGetKernelInfo',
            'clCreateProgramWithBinary', 'clGetProgramInfo'}


def parameters(text):
    values, start, depth = [], 0, 0
    for i, c in enumerate(text):
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
        elif c == ',' and depth == 0:
            values.append(text[start:i].strip())
            start = i + 1
    values.append(text[start:].strip())
    result = []
    for value in values:
        if value == 'void':
            continue
        callback = re.search(r'\(\s*CL_CALLBACK\s*\*\s*(\w+)\s*\)', value)
        match = callback or re.search(r'(\w+)\s*(?:\[[^]]*\])?\s*$', value)
        if not match:
            raise ValueError('Unsupported OpenCL parameter declaration')
        result.append(match.group(1))
    return result


def declarations(header):
    result = {}
    for name in FUNCTIONS:
        match = re.search(r'extern\s+CL_API_ENTRY\s+([\w\s*]+?)\s+CL_API_CALL\s+' + name + r'\s*\(', header)
        if not match:
            raise ValueError('Pinned OpenCL declaration missing: ' + name)
        start, depth, i = match.end(), 1, match.end()
        while depth and i < len(header):
            depth += (header[i] == '(') - (header[i] == ')')
            i += 1
        if depth:
            raise ValueError('Unterminated OpenCL declaration')
        args = header[start:i-1]
        ret = re.sub(r'\bCL_API_PREFIX__VERSION_[0-9_]+DEPRECATED\b', '', match.group(1))
        result[name] = (' '.join(ret.split()), args, parameters(args))
    return result


def generate(header, output):
    decls = declarations(header)
    code = ['// Generated from pinned Khronos OpenCL-Headers (Apache-2.0).',
            '// Do not edit. See generate_direct_opencl.py and bundled attribution.',
            '#define CL_TARGET_OPENCL_VERSION 300',
            '#define CL_USE_DEPRECATED_OPENCL_1_2_APIS',
            '#include <CL/cl.h>', '#include "direct_opencl_dispatch.h"',
            '#include <atomic>', '#include <mutex>', '#include <dlfcn.h>',
            'namespace {', 'struct Functions {']
    code += ['    decltype(&::' + n + ') ' + n + ' = nullptr;' for n in FUNCTIONS]
    code += ['};', 'Functions stable;', 'void * owner = nullptr;',
             'std::atomic<const Functions *> published{nullptr};',
             'std::atomic<const char *> missing{nullptr};',
             'std::mutex binding;', '}',
             'namespace subtitle_native::direct_opencl {',
             'bool ready() noexcept { return published.load(std::memory_order_acquire) != nullptr; }',
             'const char * missing_symbol() noexcept { return missing.load(std::memory_order_acquire); }',
             'bool bind(void * vendor) noexcept {', '    if (!vendor) return false;',
             '    try {', '        std::lock_guard<std::mutex> lock(binding);',
             '        if (published.load(std::memory_order_acquire)) return owner == vendor;',
             '        missing.store(nullptr, std::memory_order_release);',
             '        Functions candidate;']
    for name in FUNCTIONS:
        code += ['        candidate.' + name + ' = reinterpret_cast<decltype(candidate.' + name + ')>(dlsym(vendor, "' + name + '"));',
                 '        if (candidate.' + name + ' == &::' + name + ') candidate.' + name + ' = nullptr;']
        if name not in OPTIONAL:
            code += ['        if (!candidate.' + name + ') { missing.store("' + name + '", std::memory_order_release); return false; }']
    code += ['        stable = candidate;', '        owner = vendor;',
             '        published.store(&stable, std::memory_order_release);',
             '        return true;', '    } catch (...) { return false; }', '}', '}']
    for name, (ret, args, argnames) in decls.items():
        code += ['extern "C" __attribute__((visibility("hidden"))) CL_API_ENTRY ' + ret + ' CL_API_CALL ' + name + '(' + args + ') {',
                 '    const auto * api = published.load(std::memory_order_acquire);',
                 '    if (!api || !api->' + name + ') {']
        for param in ['errcode_ret']:
            if param in argnames:
                code += ['        if (' + param + ') *' + param + ' = CL_INVALID_OPERATION;']
        for param in ['num_platforms', 'num_devices', 'param_value_size_ret']:
            if param in argnames and (param != 'num_devices' or name == 'clGetDeviceIDs'):
                code += ['        if (' + param + ') *' + param + ' = 0;']
        if 'event' in argnames and name.startswith('clEnqueue'):
            code += ['        if (event) *event = nullptr;']
        if ret == 'cl_int':
            default = 'CL_PROFILING_INFO_NOT_AVAILABLE' if name == 'clGetEventProfilingInfo' else 'CL_INVALID_OPERATION'
        else:
            if ret not in {'cl_mem', 'cl_context', 'cl_command_queue', 'cl_program', 'cl_kernel', 'void *'}:
                raise ValueError('Unreviewed OpenCL return type: ' + ret)
            default = 'nullptr'
        code += ['        return ' + default + ';', '    }',
                 '    return api->' + name + '(' + ', '.join(argnames) + ');', '}']
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(code) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('header', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    generate(args.header.read_text(), args.output)
