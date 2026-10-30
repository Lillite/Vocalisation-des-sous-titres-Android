#!/usr/bin/env python3
"""Compile and exercise the real optional Vulkan dispatch with synthetic loaders.

These host checks establish forwarding, failure isolation and source-patch
boundaries. They do not execute Android, a Vulkan device or a model.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys

CORE_SHA256 = '03964df1e28a162285726ec7cf9098e5e0271cb1df21933e7bfef9f6fa08937f'

FAKE = r'''
#include <vulkan/vulkan_core.h>
#include <cassert>
#include <cstring>
static unsigned int step = 0;
static void probe() { }
extern "C" {
#if OMIT != 1
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance i, const char * n) {
    assert(i == reinterpret_cast<VkInstance>(0x11));
    if (std::strcmp(n, "first") == 0) { assert(step == 0); step = 1; }
    else { assert(std::strcmp(n, "finish") == 0 && step == 4); }
    return probe;
}
#endif
#if OMIT != 2
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice d, const char * n) {
    assert(d == reinterpret_cast<VkDevice>(0x22) && std::strcmp(n, "second") == 0 && step == 1);
    step = 2; return probe;
}
#endif
#if OMIT != 3
VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(VkPhysicalDevice d, VkPhysicalDeviceFeatures2 * f) {
    assert(d == reinterpret_cast<VkPhysicalDevice>(0x33) && f && step == 2);
    f->features.robustBufferAccess = VK_TRUE; step = 3;
}
#endif
#if OMIT != 4
VKAPI_ATTR void VKAPI_CALL vkCmdCopyBuffer(VkCommandBuffer c, VkBuffer s, VkBuffer d, uint32_t count, const VkBufferCopy * r) {
    assert(c == reinterpret_cast<VkCommandBuffer>(0x44) && s == reinterpret_cast<VkBuffer>(0x55));
    assert(d == reinterpret_cast<VkBuffer>(0x66) && count == 1 && r && step == 3);
    assert(r[0].srcOffset == 3 && r[0].dstOffset == 5 && r[0].size == 7); step = 4;
}
#endif
}
'''

CLIENT = r'''
#include "optional_vulkan_dispatch.h"
#include "optional_vulkan_probe.h"
#include <vulkan/vulkan_core.h>
#include <atomic>
#include <cassert>
#include <cstring>
#include <dlfcn.h>
#include <stdexcept>
#include <thread>
#include <vector>
struct ProbeApi {
    bool accessible = true, registered = true, gpu = true, throwing = false;
    int registrations = 0;
    bool load_driver(subtitle_native::ExecutionDiagnostic * d) {
        if (!accessible) d->reason = "DRIVER_NOT_ACCESSIBLE";
        return accessible;
    }
    bool register_backend(unsigned int * count) {
        ++registrations;
        if (throwing) throw std::runtime_error("synthetic partial registration");
        *count = gpu ? 1 : 0;
        return registered;
    }
};
void probe_cache_checks() {
    using subtitle_native::vulkan_probe::Cache;
    subtitle_native::ExecutionDiagnostic d;
    ProbeApi good;
    Cache good_cache;
    assert(good_cache.prepare(good, &d) && !d.probe_cached);
    assert(good_cache.prepare(good, &d) && d.probe_cached && good.registrations == 1);
    ProbeApi partial; partial.throwing = true;
    Cache failed_cache;
    assert(!failed_cache.prepare(partial, &d));
    assert(std::strcmp(d.reason, "UNEXPECTED_FAILURE") == 0 && partial.registrations == 1);
    partial.throwing = false;
    assert(!failed_cache.prepare(partial, &d) && d.probe_cached && partial.registrations == 1);
    ProbeApi absent; absent.accessible = false;
    Cache absent_cache;
    assert(!absent_cache.prepare(absent, &d) && absent.registrations == 0);
    assert(std::strcmp(d.stage, "VULKAN_DRIVER") == 0);
    ProbeApi unregistered; unregistered.registered = false;
    Cache unregistered_cache;
    assert(!unregistered_cache.prepare(unregistered, &d));
    assert(std::strcmp(d.reason, "REGISTRATION_FAILED") == 0);
    ProbeApi no_gpu; no_gpu.gpu = false;
    Cache no_gpu_cache;
    assert(!no_gpu_cache.prepare(no_gpu, &d) && d.gpu_devices == 0);
    assert(std::strcmp(d.reason, "NO_SUPPORTED_GPU") == 0);
    ProbeApi concurrent;
    Cache concurrent_cache;
    std::atomic<int> ready{0};
    std::atomic<bool> begin{false};
    std::vector<std::thread> threads;
    for (int i = 0; i < 16; ++i) threads.emplace_back([&] {
        while (!begin.load(std::memory_order_acquire)) std::this_thread::yield();
        subtitle_native::ExecutionDiagnostic own;
        if (concurrent_cache.prepare(concurrent, &own)) ++ready;
    });
    begin.store(true, std::memory_order_release);
    for (auto & thread : threads) thread.join();
    assert(ready == 16 && concurrent.registrations == 1);
}
int main(int argc, char ** argv) {
    assert(argc == 4);
    probe_cache_checks();
    const bool complete = std::strcmp(argv[2], "complete") == 0;
    subtitle_native::ExecutionDiagnostic diagnostic;
    if (!complete) {
        assert(!subtitle_native::bind_vulkan_test_driver(argv[1], &diagnostic));
        assert(diagnostic.reason && std::strcmp(diagnostic.reason, argv[3]) == 0);
        assert(!subtitle_native::bind_vulkan_test_driver(argv[1], &diagnostic));
        return 0;
    }
    std::atomic<int> ready{0};
    std::atomic<bool> begin{false};
    std::vector<std::thread> threads;
    for (int i = 0; i < 16; ++i) threads.emplace_back([&] {
        while (!begin.load(std::memory_order_acquire)) std::this_thread::yield();
        subtitle_native::ExecutionDiagnostic d;
        if (subtitle_native::bind_vulkan_test_driver(argv[1], &d)) ++ready;
    });
    begin.store(true, std::memory_order_release);
    for (auto & thread : threads) thread.join();
    assert(ready == 16);
    void * temporary = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(temporary); dlclose(temporary);
    assert(vkGetInstanceProcAddr(reinterpret_cast<VkInstance>(0x11), "first"));
    assert(vkGetDeviceProcAddr(reinterpret_cast<VkDevice>(0x22), "second"));
    VkPhysicalDeviceFeatures2 features{};
    vkGetPhysicalDeviceFeatures2(reinterpret_cast<VkPhysicalDevice>(0x33), &features);
    assert(features.features.robustBufferAccess == VK_TRUE);
    const VkBufferCopy region{3, 5, 7};
    vkCmdCopyBuffer(reinterpret_cast<VkCommandBuffer>(0x44), reinterpret_cast<VkBuffer>(0x55),
        reinterpret_cast<VkBuffer>(0x66), 1, &region);
    assert(vkGetInstanceProcAddr(reinterpret_cast<VkInstance>(0x11), "finish"));
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--headers', required=True, type=Path)
    parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    headers = args.headers.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if hashlib.sha256((headers / 'include/vulkan/vulkan_core.h').read_bytes()).hexdigest() != CORE_SHA256:
        raise RuntimeError('The regression requires the pinned production Vulkan headers.')
    compiler, readelf = shutil.which('g++'), shutil.which('readelf')
    if not compiler or not readelf:
        raise RuntimeError('Actual host g++ and readelf are required.')
    checks = []
    with (output / 'console.txt').open('w') as log:
        def run(command, success=True):
            result = subprocess.run([str(x) for x in command], capture_output=True, text=True, timeout=120)
            log.write(result.stdout); log.write(result.stderr); log.flush()
            if (result.returncode == 0) != success:
                raise RuntimeError('Vulkan host regression failed; see ' + str(output / 'console.txt'))
            return result.stdout

        fake, client = output / 'synthetic-vulkan.cpp', output / 'dispatch-client.cpp'
        fake.write_text(FAKE); client.write_text(CLIENT)
        include = ['-I' + str(headers / 'include'), '-I' + str(source)]
        flags = ['-std=c++17', '-O2', '-pthread', '-Wall', '-Wextra', '-Werror']
        exe = output / 'optional-vulkan-dispatch-smoke'
        run([compiler, *flags, '-fvisibility=hidden', '-DSUBTITLE_VULKAN_DISPATCH_TESTING=1',
             *include, source / 'optional_vulkan_dispatch.cpp', client, '-ldl', '-o', exe])
        for omit in range(5):
            library = output / f'libsynthetic-vulkan-{omit}.so'
            run([compiler, *flags, '-shared', '-fPIC', '-DOMIT=' + str(omit), *include, fake, '-o', library])
            mode = 'complete' if omit == 0 else 'missing'
            run([exe, library, mode, 'ENTRY_POINTS_MISSING'])
            checks.append(mode if omit == 0 else f'missing-entry-{omit}')
        run([exe, output / 'absent-vulkan-loader.so', 'missing', 'DRIVER_NOT_ACCESSIBLE'])
        checks.append('absent-library')
        checks.append('probe-registration-failure-sealed-and-concurrent-cache')
        symbols = run([readelf, '--dyn-syms', '-W', exe])
        if any(re.fullmatch(r'vk[A-Z]\w*', line.split()[-1].split('@')[0])
               for line in symbols.splitlines() if line.split()):
            raise RuntimeError('The optional Vulkan loader entry points must remain hidden and resolved.')
        checks.append('no-exported-or-unresolved-vulkan-entry-points')
        upstream = args.upstream.resolve()
        original = upstream.read_bytes()
        generated = output / 'instrumented.cpp'
        run([sys.executable, source / 'optional_vulkan_patch.py', upstream, generated])
        if upstream.read_bytes() != original:
            raise RuntimeError('The instrumentation must not modify upstream source.')
        text = generated.read_text()
        if text.count('subtitle_vulkan_graph_executed(subtitle_submitted_nodes);') != 1:
            raise RuntimeError('Expected one success-path observation.')
        enqueued = '''        if (enqueued) {
            ++submitted_nodes;
            subtitle_submitted_nodes += 1 + static_cast<uint64_t>(ctx->num_additional_fused_ops);
'''
        if text.count(enqueued) != 1 or text.count('uint64_t subtitle_submitted_nodes = 0;') != 1:
            raise RuntimeError('Only successfully enqueued graph operations may be observed.')
        checks.append('pinned-graph-success-instrumentation')
        altered = output / 'altered-upstream.cpp'
        altered.write_bytes(original.replace(b'static ggml_status ggml_backend_vk_graph_compute(',
                                            b'static ggml_status changed_graph_signature(', 1))
        run([sys.executable, source / 'optional_vulkan_patch.py', altered, output / 'refused.cpp'], success=False)
        checks.append('changed-upstream-anchor-refused')
    summary = dict(checks=checks, passed=len(checks), android_executed=False,
                   gpu_executed=False, model_executed=False)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
