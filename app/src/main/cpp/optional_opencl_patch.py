#!/usr/bin/env python3
"""Instrument only the pinned OpenCL graph execution, in a generated build copy.

No model tensors, graph node names, user text or paths enter the counter. A
successful graph call records submitted compute nodes; the owner must synchronize
its context successfully before describing them as observed GPU execution.
"""
from pathlib import Path
import hashlib
import re
import sys

source, destination = map(Path, sys.argv[1:])
text = source.read_text(encoding='utf-8')
if hashlib.sha256(text.encode()).hexdigest() != 'c43cd480a6ddbf2a6bcac95baca74cc94b40c0da3a9b3e30a6e90d49f2933c33':
    raise SystemExit('Pinned OpenCL source bytes changed; optimization refused')

def replace_once(old, new):
    global text
    if text.count(old) != 1:
        raise SystemExit('Pinned OpenCL optimization anchor changed; refused')
    text = text.replace(old, new, 1)

replace_once('#include "cl-program-cache.h"', '#include "cl-program-cache.h"\n#include "opencl_memory_observation.h"\n#include "opencl_q6_upload.h"\n#include <array>')

# All consumers already grow prealloc_act_trans immediately before use. The
# other two buffers have no consumers in this exact revision. Do not allocate
# 376.8 MiB just because a backend is created.
initial_start = text.index('    // Allocate intermediate buffers and images\n')
initial_end = text.index('#endif // GGML_OPENCL_USE_ADRENO_KERNELS', initial_start)
initial = text[initial_start:initial_end]
for name, count in [('prealloc_quant_trans', 2), ('prealloc_scales_trans', 2)]:
    if len(re.findall(r'\b' + name + r'\b', text)) != count:
        raise SystemExit('Previously unused OpenCL reserve gained a consumer; refused')
if initial.count('.allocate(context, ') != 3:
    raise SystemExit('Pinned initial reserve count changed; refused')
text = text[:initial_start] + '    // Scratch is allocated lazily by its actual consumer.\n    subtitle_native::opencl_memory::skipped(395149312ULL);\n' + text[initial_end:]

# Scratch ownership follows the backend, never a process-static variable that
# survives release of a model or belongs to the wrong cl_context.
static_pattern = re.compile(r'(?m)^(\s*)static ggml_cl_buffer (\w+);$')
statics = list(static_pattern.finditer(text))
if len(statics) != 23:
    raise SystemExit('Pinned OpenCL scratch ownership changed; refused')
slot = 0
def owned_static(match):
    global slot
    value = f'{match.group(1)}auto & {match.group(2)} = backend_ctx->subtitle_owned_scratch[{slot}];'
    slot += 1
    return value
text = static_pattern.sub(owned_static, text)
replace_once('    // prealloc buffers for transposing weights and activations\n',
             '    std::array<ggml_cl_buffer, 23> subtitle_owned_scratch;\n\n    // prealloc buffers for transposing weights and activations\n')

wrapper_start = text.index('struct ggml_cl_buffer {')
wrapper_end = text.index('\n};', wrapper_start) + 3
text = text[:wrapper_start] + '''struct ggml_cl_buffer {
    cl_mem buffer = nullptr;
    size_t size = 0;
    ggml_cl_buffer() = default;
    ggml_cl_buffer(const ggml_cl_buffer &) = delete;
    ggml_cl_buffer & operator=(const ggml_cl_buffer &) = delete;
    ~ggml_cl_buffer() { reset(); }
    void reset() {
        if (buffer) {
            CL_CHECK(clReleaseMemObject(buffer));
            subtitle_native::opencl_memory::scratch_release(size);
            buffer = nullptr;
            size = 0;
        }
    }
    void allocate(cl_context context, size_t new_size) {
        if (new_size <= size) return;
        // Release the old scratch first, preserving upstream's low-peak policy.
        reset();
        cl_int err;
        buffer = clCreateBuffer(context, CL_MEM_READ_WRITE, new_size, NULL, &err);
        if (err != CL_SUCCESS) subtitle_native::opencl_memory::failed();
        CL_CHECK(err);
        size = new_size;
        subtitle_native::opencl_memory::scratch_allocate(size);
    }
};''' + text[wrapper_end:]
replace_once('''            if (fa.fd_partial.buffer) {
                CL_CHECK(clReleaseMemObject(fa.fd_partial.buffer));
                fa.fd_partial.buffer = nullptr;
                fa.fd_partial.size = 0;
            }''', '            fa.fd_partial.reset();')

# These wrappers own scratch only. All queued work and pooled aliases have
# completed before ref_count reaches zero; release their retained capacities.
scratch_names = re.findall(r'^    ggml_cl_buffer (prealloc_\w+);', text, re.M)
if len(scratch_names) != 21:
    raise SystemExit('Pinned OpenCL backend scratch fields changed; refused')
resets = ''.join('            ' + name + '.reset();\n' for name in scratch_names if name != 'prealloc_adreno_xmem_const')
replace_once('''            ggml_cl_adreno_xmem_attn_release_scratch(this);
#endif
        }
    }
};''', '''            ggml_cl_adreno_xmem_attn_release_scratch(this);
            prealloc_adreno_xmem_const.reset();
#endif
            for (auto & scratch : subtitle_owned_scratch) scratch.reset();
''' + resets + '''            subtitle_native::opencl_memory::pools(0, 0);
        }
    }
};''')

# Pools contain either aliases or owned dequantization scratch. They may be
# cleared only at a completed graph boundary, never while an operand returned
# by a lookup is waiting to be enqueued by the current attention operation.
pool_method = '''    void subtitle_bound_pools() {
        uint64_t entries = kq_img_pool.size() + kqv_img_pool.size() + dequant_f16_pool.size();
        uint64_t covered = 0, dequant = 0;
        for (const auto & kv : kq_img_pool) covered += kv.second.k_bytes;
        for (const auto & kv : kqv_img_pool) covered += kv.second.k_bytes;
        for (const auto & kv : dequant_f16_pool) { covered += kv.second.k_bytes; dequant += kv.second.k_bytes; }
        if (entries > 512 || dequant > 256ULL * 1024 * 1024) {
            CL_CHECK(clFinish(queue));
            for (auto * pool : {&kq_img_pool, &kqv_img_pool, &dequant_f16_pool}) {
                for (auto & kv : *pool) {
                    if (kv.second.image) CL_CHECK(clReleaseMemObject(kv.second.image));
                    if (kv.second.sub_buffer) CL_CHECK(clReleaseMemObject(kv.second.sub_buffer));
                }
                pool->clear();
            }
            entries = covered = 0;
        }
        subtitle_native::opencl_memory::pools(entries, covered);
    }

'''
replace_once('''    void free() {
        clFinish(queue);''', pool_method + '''    void free() {
        clFinish(queue);''')

# Observe temporary transfer requests and the driver's existing fallback. No
# user/tensor names enter telemetry.
upload_start = text.index('static cl_mem ggml_cl_create_temp_upload_buffer(')
upload_end = text.index('\n}\n', upload_start) + 2
upload = text[upload_start:upload_end]
upload = upload.replace('    cl_int err;', '    subtitle_native::opencl_memory::upload(nbytes);\n    cl_int err;', 1)
upload = upload.replace('    if (err != CL_SUCCESS) {', '    if (err != CL_SUCCESS) {\n        subtitle_native::opencl_memory::failed();')
upload = upload.replace('    buf = clCreateBuffer(context,\n', '    subtitle_native::opencl_memory::host_fallback();\n    buf = clCreateBuffer(context,\n', 1)
text = text[:upload_start] + upload + text[upload_end:]

# Large dense Q6_K conversion no longer requires a second whole-tensor GPU
# upload. Build exact SoA planes in a bounded host workspace, then use the same
# upstream transpositions and compute kernels. Special MoE/tiled layouts keep
# their original converter; this path never decodes or requantizes weights.
q6_start = text.index('    if (tensor->type == GGML_TYPE_Q6_K) {', text.index('static void ggml_backend_opencl_buffer_set_tensor('))
q6_end = text.index('\n#endif // GGML_OPENCL_SOA_Q', q6_start)
q6 = text[q6_start:q6_end]
upload_old = '''        cl_mem data_device = ggml_cl_create_temp_upload_buffer(context, queue, ggml_nbytes(tensor), data, tensor->name);
        GGML_ASSERT(data_device != NULL && "q6_K set_tensor: temp upload buffer alloc failed");'''
if q6.count(upload_old) != 1:
    raise SystemExit('Pinned Q6 upload anchor changed; refused')
q6 = q6.replace(upload_old, '        cl_mem data_device = nullptr;')
for branch in ('        if (use_adreno_moe_kernels(backend_ctx, tensor)) {', '        if (use_q6k_tiled(backend_ctx, tensor)) {'):
    if q6.count(branch) != 1:
        raise SystemExit('Pinned specialized Q6 branch changed; refused')
    q6 = q6.replace(branch, branch + '\n            data_device = ggml_cl_create_temp_upload_buffer(context, queue, ggml_nbytes(tensor), data, tensor->name);\n            GGML_ASSERT(data_device != NULL && "q6_K specialized upload allocation failed");', 1)
flat_start = q6.index('        // Flatten the weights\n')
flat_end = q6.index('        extra->size_ql = size_ql;', flat_start)
flat = q6[flat_start:flat_end]
flat = flat.replace('        // Flatten the weights\n', '''        data_device = ggml_cl_create_temp_upload_buffer(context, queue, ggml_nbytes(tensor), data, tensor->name);
        GGML_ASSERT(data_device != NULL && "q6_K upload allocation failed");
        // Flatten the weights
''', 1)
stream = '''        if (ggml_nbytes(tensor) > subtitle_native::opencl_q6::stream_threshold) {
            const size_t blocks = ggml_nelements(tensor) / ggml_blck_size(tensor->type);
            GGML_ASSERT(ggml_type_size(tensor->type) == subtitle_native::opencl_q6::block_bytes);
            bool noshuffle = false;
#ifdef GGML_OPENCL_USE_ADRENO_KERNELS
            noshuffle = use_adreno_kernels(backend_ctx, tensor) && !use_flat_gemv_for_large_m_q6_K(backend_ctx, tensor);
#endif
            const size_t capacity = std::min(blocks, subtitle_native::opencl_q6::chunk_blocks);
            std::vector<uint8_t> workspace(capacity * subtitle_native::opencl_q6::block_bytes);
            uint8_t * ql = workspace.data();
            uint8_t * qh = ql + capacity * 128;
            uint8_t * scales = qh + capacity * 64;
            uint8_t * d = scales + capacity * 16;
            subtitle_native::opencl_memory::upload(workspace.size());
            for (size_t first = 0; first < blocks; first += capacity) {
                const size_t count = std::min(capacity, blocks - first);
                subtitle_native::opencl_q6::pack(static_cast<const uint8_t *>(data) + first * 210, count, noshuffle, ql, qh, scales, d);
                CL_CHECK(clEnqueueWriteBuffer(queue, extra->ql, CL_TRUE, first * 128, count * 128, ql, 0, NULL, NULL));
                CL_CHECK(clEnqueueWriteBuffer(queue, extra->qh, CL_TRUE, first * 64, count * 64, qh, 0, NULL, NULL));
                CL_CHECK(clEnqueueWriteBuffer(queue, extra->s, CL_TRUE, first * 16, count * 16, scales, 0, NULL, NULL));
                CL_CHECK(clEnqueueWriteBuffer(queue, extra->d, CL_TRUE, first * 2, count * 2, d, 0, NULL, NULL));
            }
        } else {
''' + flat + '''        }

'''
q6 = q6[:flat_start] + stream + q6[flat_end:]
if q6.count('CL_CHECK(clWaitForEvents(1, &evt));') != 3:
    raise SystemExit('Pinned Q6 upload event ownership changed; refused')
q6 = q6.replace('CL_CHECK(clWaitForEvents(1, &evt));',
                'CL_CHECK(clWaitForEvents(1, &evt));\n            CL_CHECK(clReleaseEvent(evt));')
text = text[:q6_start] + q6 + text[q6_end:]

# Constant weight views belong to the tensor extra; activation views remain
# per-dispatch. Recreate only when the exact view width changes, after draining
# the queue that could still reference the preceding view.
extra_start = text.index('struct ggml_tensor_extra_cl_q6_K {')
extra_end = text.index('\n};', extra_start) + 3
extra = text[extra_start:extra_end]
extra = extra.replace('    // Lower 4 bits of quantized weights.', '''    cl_mem subtitle_ql_f32_image = nullptr, subtitle_qh_f16_image = nullptr;
    size_t subtitle_ql_width = 0, subtitle_qh_width = 0;
    cl_mem subtitle_dense_image(cl_context context, cl_command_queue queue, bool lower, size_t width) {
        cl_mem & image = lower ? subtitle_ql_f32_image : subtitle_qh_f16_image;
        size_t & previous = lower ? subtitle_ql_width : subtitle_qh_width;
        if (image && previous == width) return image;
        if (image) {
            CL_CHECK(clFinish(queue));
            CL_CHECK(clReleaseMemObject(image));
            image = nullptr;
        }
        cl_image_format format = {CL_R, static_cast<cl_channel_type>(lower ? CL_FLOAT : CL_HALF_FLOAT)};
        cl_image_desc description = {};
        description.image_type = CL_MEM_OBJECT_IMAGE1D_BUFFER;
        description.image_width = width;
        description.buffer = lower ? ql : qh;
        cl_int error;
        image = clCreateImage(context, CL_MEM_READ_ONLY, &format, &description, NULL, &error);
        if (error != CL_SUCCESS) subtitle_native::opencl_memory::failed();
        CL_CHECK(error);
        previous = width;
        return image;
    }
    // Lower 4 bits of quantized weights.''', 1)
extra = extra.replace('    void reset() {', '''    void reset() {
        if (subtitle_ql_f32_image) { CL_CHECK(clReleaseMemObject(subtitle_ql_f32_image)); subtitle_ql_f32_image = nullptr; }
        if (subtitle_qh_f16_image) { CL_CHECK(clReleaseMemObject(subtitle_qh_f16_image)); subtitle_qh_f16_image = nullptr; }
        subtitle_ql_width = subtitle_qh_width = 0;''', 1)
text = text[:extra_start] + extra + text[extra_end:]
replace_once('''            CL_CHECK((ql_img = clCreateImage(context, CL_MEM_READ_ONLY, &img_fmt, &img_desc, NULL, &err), err));''',
             '            ql_img = extra0_q6_K->subtitle_dense_image(context, backend_ctx->queue, true, img_desc.image_width);')
replace_once('''            CL_CHECK((qh_img = clCreateImage(context, CL_MEM_READ_ONLY, &img_fmt, &img_desc, NULL, &err), err));''',
             '            qh_img = extra0_q6_K->subtitle_dense_image(context, backend_ctx->queue, false, img_desc.image_width);')
replace_once('''        if (ql_img) CL_CHECK(clReleaseMemObject(ql_img));
        if (qh_img) CL_CHECK(clReleaseMemObject(qh_img));''',
             '        // Weight image views remain owned by extra0_q6_K until reset.')

start = 'static ggml_status ggml_backend_opencl_graph_compute(ggml_backend_t backend, ggml_cgraph * cgraph) {'
if text.count(start) != 1:
    raise SystemExit('Pinned OpenCL graph signature changed; instrumentation refused')
first = text.index(start)
last = text.index('\n}', first) + 2
block = text[first:last]
marker = '''        if ((node->flags & GGML_TENSOR_FLAG_COMPUTE) == 0) {
            continue;
        }
'''
if block.count(marker) != 1 or block.count('    return GGML_STATUS_SUCCESS;') != 1:
    raise SystemExit('Pinned OpenCL graph anchors changed; instrumentation refused')
block = block.replace(start, start + '\n    uint64_t subtitle_submitted_nodes = 0;')
block = block.replace(marker, marker + '\n        ++subtitle_submitted_nodes;\n')
block = block.replace('    return GGML_STATUS_SUCCESS;', '    backend_ctx->subtitle_bound_pools();\n    subtitle_opencl_graph_executed(subtitle_submitted_nodes);\n    return GGML_STATUS_SUCCESS;')
block = 'extern "C" void subtitle_opencl_graph_executed(uint64_t nodes);\n' + block
output = text[:first] + block + text[last:]
destination.parent.mkdir(parents=True, exist_ok=True)
if not destination.exists() or destination.read_text(encoding='utf-8') != output:
    destination.write_text(output, encoding='utf-8')
