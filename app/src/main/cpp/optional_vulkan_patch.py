#!/usr/bin/env python3
"""Observe successful pinned Vulkan graph submissions in a generated build copy.

Only graph counts are recorded. The caller must synchronize successfully before
publishing GPU execution. Upstream source and its pinned archive stay unchanged.
"""
from pathlib import Path
import sys

source, destination = map(Path, sys.argv[1:])
text = source.read_text(encoding='utf-8')
signature = 'static ggml_status ggml_backend_vk_graph_compute(ggml_backend_t backend, ggml_cgraph * cgraph) {'
if text.count(signature) != 1:
    raise SystemExit('Pinned Vulkan graph signature changed; instrumentation refused')
first = text.index(signature)
last = text.index('\n}', first) + 2
block = text[first:last]
marker = '    return GGML_STATUS_SUCCESS;'
enqueue = '''        if (enqueued) {
            ++submitted_nodes;
'''
if block.count(marker) != 1 or block.count(enqueue) != 1:
    raise SystemExit('Pinned Vulkan graph completion anchor changed; instrumentation refused')
block = block.replace(signature, signature + '\n    uint64_t subtitle_submitted_nodes = 0;')
# build_graph can reject a graph node even if it is nonempty. Observe only
# successful enqueues, counting the logical graph operations in a fused group.
block = block.replace(enqueue, enqueue +
    '            subtitle_submitted_nodes += 1 + static_cast<uint64_t>(ctx->num_additional_fused_ops);\n')
block = block.replace(marker,
    '    subtitle_vulkan_graph_executed(subtitle_submitted_nodes);\n' + marker)
block = 'extern "C" void subtitle_vulkan_graph_executed(uint64_t nodes);\n' + block
output = text[:first] + block + text[last:]
destination.parent.mkdir(parents=True, exist_ok=True)
if not destination.exists() or destination.read_text(encoding='utf-8') != output:
    destination.write_text(output, encoding='utf-8')
