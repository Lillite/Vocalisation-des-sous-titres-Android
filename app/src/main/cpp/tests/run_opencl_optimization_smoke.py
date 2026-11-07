#!/usr/bin/env python3
"""Verify exact Q6 layout, scratch ownership and generated OpenCL source.

These are host regressions using the pinned kernels as a byte-layout oracle.
They do not run an Android driver or establish GPU performance or fidelity.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--llama-source', required=True, type=Path)
parser.add_argument('--headers', type=Path)
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
upstream = args.llama_source.resolve()
source = upstream / 'ggml/src/ggml-opencl/ggml-opencl.cpp'
cvt = (upstream / 'ggml/src/ggml-opencl/kernels/cvt.cl').read_text()

def function(text, marker):
    first = text.index(marker)
    return text[first:text.index('\n}', first) + 2]

def execute(command, log):
    result = subprocess.run(command, capture_output=True, text=True)
    log.write_text(result.stdout + result.stderr)
    if result.returncode:
        raise SystemExit(log.read_text()[-12000:])

with tempfile.TemporaryDirectory(prefix='subtitle-opencl-optimization-') as temporary:
    output = args.output.resolve() if args.output else Path(temporary)
    output.mkdir(parents=True, exist_ok=True)
    patched = output / 'ggml-opencl-optimized.cpp'
    command = ['python3', str(root / 'optional_opencl_patch.py'), str(source), str(patched)]
    subprocess.run(command, check=True)
    original_bytes = patched.read_bytes()
    subprocess.run(command, check=True)
    assert patched.read_bytes() == original_bytes, 'Generation must be byte-idempotent'
    text = patched.read_text()
    assert 'static ggml_cl_buffer ' not in text
    assert 'prealloc_quant_trans.allocate' not in text and 'prealloc_scales_trans.allocate' not in text
    assert 'prealloc_act_trans.allocate(context, max_B_d_bytes)' not in text
    assert text.count('backend_ctx->subtitle_owned_scratch[') == 23
    assert text.count('for (auto & scratch : subtitle_owned_scratch) scratch.reset();') == 1
    assert text.index('clFinish(queue);', text.index('    void free()')) < text.index('scratch.reset();', text.index('    void free()'))
    assert 'ggml_nbytes(tensor) > subtitle_native::opencl_q6::stream_threshold' in text
    corrupt = output / 'changed-upstream.cpp'
    corrupt.write_text(source.read_text() + '\n// changed dependency\n')
    refused = subprocess.run(['python3', str(root / 'optional_opencl_patch.py'), str(corrupt), str(output / 'refused.cpp')], capture_output=True)
    assert refused.returncode and not (output / 'refused.cpp').exists()

    wrapper = text[text.index('struct ggml_cl_buffer {'):text.index('\n};', text.index('struct ggml_cl_buffer {')) + 3]
    # The method is indented, so its final closing brace is followed by the
    # next member rather than a column-zero brace.
    pool_start = text.index('    void subtitle_bound_pools()')
    pool_method = text[pool_start:text.index('\n    void free()', pool_start)]
    extra_start = text.index('struct ggml_tensor_extra_cl_q6_K {')
    q6_extra = text[extra_start:text.index('\n};', extra_start) + 3]
    kernels = '\n'.join(function(cvt, 'kernel void ' + name + '(')
                        for name in ('kernel_convert_block_q6_K', 'kernel_convert_block_q6_K_noshuffle'))
    struct_first = cvt.index('struct block_q6_K {')
    block_struct = cvt[struct_first:cvt.index('\n};', struct_first) + 3]
    harness = output / 'opencl-optimization.cpp'
    harness.write_text(r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <map>
#include <stdexcept>
#include <vector>
#include "opencl_memory_observation.h"
#include "opencl_q6_upload.h"
using cl_context = void *;
using cl_command_queue = void *;
using cl_int = int;
using cl_channel_type = unsigned int;
struct FakeBuffer { size_t size; };
using cl_mem = FakeBuffer *;
constexpr int CL_SUCCESS = 0, CL_MEM_READ_WRITE = 1, CL_MEM_READ_ONLY = 2;
constexpr unsigned int CL_R = 1, CL_FLOAT = 2, CL_HALF_FLOAT = 3, CL_MEM_OBJECT_IMAGE1D_BUFFER = 4;
struct cl_image_format { unsigned int image_channel_order, image_channel_data_type; };
struct cl_image_desc { unsigned int image_type; size_t image_width; cl_mem buffer; };
static int creates = 0, releases = 0, finishes = 0;
static bool refuse_allocate = false;
cl_mem clCreateBuffer(cl_context, int, size_t n, void *, int * status) {
    if (refuse_allocate) { *status = -4; return nullptr; }
    *status = 0; ++creates; return new FakeBuffer{n};
}
int clReleaseMemObject(cl_mem value) { assert(value); ++releases; delete value; return 0; }
int clFinish(void *) { ++finishes; return 0; }
cl_mem clCreateImage(cl_context, int, const cl_image_format *, const cl_image_desc * desc, void *, int * status) {
    *status = 0; ++creates; return new FakeBuffer{desc->image_width};
}
#define CL_CHECK(x) do { if ((x) != 0) throw std::runtime_error("synthetic OpenCL failure"); } while (0)
''' + wrapper + '\n' + q6_extra + r'''
struct PoolContext {
    struct Entry { cl_mem image = nullptr, sub_buffer = nullptr; uint64_t k_bytes = 0; };
    std::map<int, Entry> kq_img_pool, kqv_img_pool, dequant_f16_pool;
    void * queue = nullptr;
''' + pool_method + r'''
};
namespace pinned {
using uchar = unsigned char;
using ulong = unsigned long;
using half = uint16_t;
constexpr int QK_K = 256;
static size_t gid = 0;
size_t get_global_id(int) { return gid; }
#define kernel
#define global
''' + block_struct + '\n' + kernels + r'''
#undef global
#undef kernel
}
int main() {
    using namespace subtitle_native;
    {
        ggml_cl_buffer scratch;
        scratch.allocate(nullptr, 64);
        auto a = opencl_memory::snapshot();
        assert(a.scratch_current_bytes == 64 && a.scratch_peak_bytes == 64);
        scratch.allocate(nullptr, 32);
        assert(creates == 1 && releases == 0);
        scratch.allocate(nullptr, 128);
        assert(creates == 2 && releases == 1);
        assert(opencl_memory::snapshot().scratch_peak_bytes == 128);
        scratch.reset(); scratch.reset();
        assert(opencl_memory::snapshot().scratch_current_bytes == 0 && releases == 2);
        refuse_allocate = true;
        try { scratch.allocate(nullptr, 128); assert(false); } catch (const std::runtime_error &) { }
        assert(!scratch.buffer && scratch.size == 0);
        assert(opencl_memory::snapshot().allocation_failures == 1);
        refuse_allocate = false;
        scratch.allocate(nullptr, 16);
    }
    assert(opencl_memory::snapshot().scratch_current_bytes == 0 && creates == releases);
    {
        ggml_tensor_extra_cl_q6_K extra;
        cl_int status;
        extra.ql = clCreateBuffer(nullptr, CL_MEM_READ_WRITE, 256, nullptr, &status);
        extra.qh = clCreateBuffer(nullptr, CL_MEM_READ_WRITE, 128, nullptr, &status);
        const int before = creates, before_release = releases;
        auto first = extra.subtitle_dense_image(nullptr, nullptr, true, 16);
        assert(first == extra.subtitle_dense_image(nullptr, nullptr, true, 16));
        assert(creates == before + 1 && releases == before_release);
        extra.subtitle_dense_image(nullptr, nullptr, false, 16);
        extra.subtitle_dense_image(nullptr, nullptr, true, 32);
        assert(finishes == 1 && creates == before + 3 && releases == before_release + 1);
        extra.reset(); extra.reset();
        assert(!extra.subtitle_ql_f32_image && !extra.subtitle_qh_f16_image && !extra.ql && !extra.qh);
    }
    assert(creates == releases);
    finishes = 0;
    {
        PoolContext ctx;
        ctx.kq_img_pool[1] = {new FakeBuffer{1}, new FakeBuffer{1}, 1ULL << 30};
        ctx.subtitle_bound_pools();
        assert(ctx.kq_img_pool.size() == 1 && finishes == 0); // aliased coverage is not owned RAM
        ctx.dequant_f16_pool[1] = {new FakeBuffer{1}, nullptr, 257ULL << 20};
        ctx.subtitle_bound_pools();
        assert(ctx.kq_img_pool.empty() && ctx.dequant_f16_pool.empty() && finishes == 1);
        assert(opencl_memory::snapshot().pool_entries == 0);
        for (int i = 0; i < 513; ++i) ctx.kqv_img_pool[i] = {new FakeBuffer{1}, nullptr, 16};
        ctx.subtitle_bound_pools();
        assert(ctx.kqv_img_pool.empty() && finishes == 2);
    }
    static_assert(sizeof(pinned::block_q6_K) == opencl_q6::block_bytes);
    constexpr size_t count = opencl_q6::chunk_blocks * 2 + 37;
    std::vector<uint8_t> source(count * 210);
    uint32_t rng = 0x721a34u;
    for (auto & byte : source) { rng = rng * 1664525u + 1013904223u; byte = rng >> 24; }
    for (bool shuffled : {false, true}) {
        std::vector<uint8_t> lo(count * 128), hi(count * 64), scales(count * 16), d(count * 2);
        std::vector<uint8_t> reflo(lo.size()), refhi(hi.size()), refs(scales.size()), refd(d.size());
        for (size_t first = 0; first < count; first += opencl_q6::chunk_blocks) {
            const size_t n = std::min(opencl_q6::chunk_blocks, count - first);
            opencl_q6::pack(source.data() + first * 210, n, shuffled,
                lo.data() + first * 128, hi.data() + first * 64, scales.data() + first * 16, d.data() + first * 2);
        }
        for (pinned::gid = 0; pinned::gid < count; ++pinned::gid) {
            auto converter = shuffled ? pinned::kernel_convert_block_q6_K_noshuffle : pinned::kernel_convert_block_q6_K;
            converter(reinterpret_cast<pinned::block_q6_K *>(source.data()), reflo.data(), refhi.data(),
                reinterpret_cast<char *>(refs.data()), reinterpret_cast<uint16_t *>(refd.data()), 255, count);
        }
        assert(lo == reflo && hi == refhi && scales == refs && d == refd);
    }
    std::cout << "Exact Q6 byte layouts: " << count << " blocks x 2 modes, across chunk boundaries; scratch ownership/failure and safe graph-boundary pool limits passed.\n";
}
''')
    executable = output / 'opencl-optimization'
    execute(['c++', '-std=c++17', '-O2', '-I', str(root), str(harness), '-o', str(executable)], output / 'compile-host.log')
    execute([str(executable)], output / 'host-regressions.log')
    syntax_checked = False
    if args.headers:
        command = ['c++', '-std=c++17', '-fsyntax-only', '-DGGML_OPENCL_TARGET_VERSION=300',
                   '-DGGML_OPENCL_SOA_Q', '-DGGML_OPENCL_USE_ADRENO_KERNELS']
        for include in (root, args.headers.resolve(), upstream / 'ggml/include', upstream / 'ggml/src',
                        upstream / 'ggml/src/ggml-opencl', upstream / 'vendor'):
            command += ['-I', str(include)]
        command += [str(patched)]
        execute(command, output / 'full-backend-syntax.log')
        syntax_checked = True
    summary = {'status': 'passed', 'q6ComparedBlocksPerMode': 65573, 'q6Modes': 2,
               'checks': ['pinned-source refusal', 'idempotency', 'scratch ownership and failure',
                          'constant Q6 image view reuse and release', 'graph-boundary pool limits',
                          'byte-exact upstream Q6 conversion'],
               'fullBackendHostSyntaxChecked': syntax_checked, 'physicalGpuExecuted': False,
               'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
               'generatedSha256': hashlib.sha256(patched.read_bytes()).hexdigest()}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print((output / 'host-regressions.log').read_text().strip())
    print(json.dumps(summary))
