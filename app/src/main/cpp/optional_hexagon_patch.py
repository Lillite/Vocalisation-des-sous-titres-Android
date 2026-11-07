#!/usr/bin/env python3
"""Observe successful HTP submissions in a generated copy of the pinned backend.

Copies and fences alone never count as NPU computation. The application only
publishes these request-local counts after llama_synchronize has completed.
"""
from pathlib import Path
import sys

source, destination = map(Path, sys.argv[1:])
text = source.read_text(encoding="utf-8")
signature = "static ggml_status ggml_backend_hexagon_graph_compute(ggml_backend_t backend, ggml_cgraph * graph) {"
if text.count(signature) != 1:
    raise SystemExit("Pinned Hexagon graph signature changed; instrumentation refused")
first = text.index(signature)
last = text.index("\n}", first) + 2
block = text[first:last]
marker = "    return GGML_STATUS_SUCCESS;"
if block.count(marker) != 1 or block.count("    for (const auto & node : *nodes_ptr) {") != 1:
    raise SystemExit("Pinned Hexagon graph completion anchor changed; instrumentation refused")
observation = """    uint64_t subtitle_submitted_nodes = 0;
    for (const auto & node : *nodes_ptr) {
        if (node.node && op_is_compute(const_cast<ggml_tensor *>(node.node)) &&
            node.opcode != HTP_OP_CPY && node.opcode != HTP_OP_CPY_FENCE &&
            node.opcode != HTP_OP_FENCE && node.opcode != HTP_OP_MDEV_GROUP) {
            ++subtitle_submitted_nodes;
        }
    }
    subtitle_hexagon_graph_executed(subtitle_submitted_nodes);
"""
block = block.replace(marker, observation + marker)
block = 'extern "C" void subtitle_hexagon_graph_executed(uint64_t nodes);\n' + block
output = text[:first] + block + text[last:]
destination.parent.mkdir(parents=True, exist_ok=True)
if not destination.exists() or destination.read_text(encoding="utf-8") != output:
    destination.write_text(output, encoding="utf-8")
