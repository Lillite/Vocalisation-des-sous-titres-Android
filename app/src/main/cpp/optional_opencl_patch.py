#!/usr/bin/env python3
"""Instrument only the pinned OpenCL graph execution, in a generated build copy.

No model tensors, graph node names, user text or paths enter the counter. A
successful graph call records submitted compute nodes; the owner must synchronize
its context successfully before describing them as observed GPU execution.
"""
from pathlib import Path
import sys

source, destination = map(Path, sys.argv[1:])
text = source.read_text(encoding='utf-8')
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
block = block.replace('    return GGML_STATUS_SUCCESS;', '    subtitle_opencl_graph_executed(subtitle_submitted_nodes);\n    return GGML_STATUS_SUCCESS;')
block = 'extern "C" void subtitle_opencl_graph_executed(uint64_t nodes);\n' + block
output = text[:first] + block + text[last:]
destination.parent.mkdir(parents=True, exist_ok=True)
if not destination.exists() or destination.read_text(encoding='utf-8') != output:
    destination.write_text(output, encoding='utf-8')
