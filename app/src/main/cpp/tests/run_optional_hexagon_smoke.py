#!/usr/bin/env python3
"""Check optional-driver failure isolation and pinned observation boundaries.

This host test does not execute FastRPC, an Android device, or an NPU model.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile

parser = argparse.ArgumentParser()
upstream = parser.add_mutually_exclusive_group(required=True)
upstream.add_argument("--llama-source", type=Path)
upstream.add_argument("--upstream", type=Path)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
source = args.upstream if args.upstream else args.llama_source / "ggml/src/ggml-hexagon/ggml-hexagon.cpp"
with tempfile.TemporaryDirectory(prefix="subtitle-hexagon-host-") as directory:
    output = Path(directory)
    for name in ["optional_hexagon_diagnostic_smoke", "model_memory_budget_smoke"]:
        executable = output / name
        subprocess.run(["c++", "-std=c++17", "-O2", "-pthread", "-I", str(root),
                        str(root / f"tests/{name}.cpp"), "-o", str(executable)], check=True)
        subprocess.run([str(executable)], check=True)
    patched = output / "instrumented.cpp"
    command = ["python3", str(root / "optional_hexagon_patch.py"), str(source), str(patched)]
    subprocess.run(command, check=True)
    first = patched.read_bytes()
    subprocess.run(command, check=True)
    assert patched.read_bytes() == first, "Patching must be idempotent"
    text = first.decode()
    assert text.count("subtitle_hexagon_graph_executed(subtitle_submitted_nodes);") == 1
    begin = text.index("static ggml_status ggml_backend_hexagon_graph_compute(")
    end = text.index("\n}", begin)
    graph = text[begin:end]
    assert graph.rindex("if (sess->last_error > HTP_STATUS_OK)") < graph.index("subtitle_hexagon_graph_executed(subtitle_submitted_nodes);")
    assert graph.index("subtitle_hexagon_graph_executed(subtitle_submitted_nodes);") < graph.index("return GGML_STATUS_SUCCESS;")
    for opcode in ["HTP_OP_CPY", "HTP_OP_CPY_FENCE", "HTP_OP_FENCE", "HTP_OP_MDEV_GROUP"]:
        assert "node.opcode != " + opcode in graph
    corrupt = output / "changed.cpp"
    corrupt.write_text(source.read_text().replace("    return GGML_STATUS_SUCCESS;", "    return GGML_STATUS_SUCCESS;\n    return GGML_STATUS_SUCCESS;"))
    refused = subprocess.run(["python3", str(root / "optional_hexagon_patch.py"), str(corrupt), str(output / "refused.cpp")], capture_output=True)
    assert refused.returncode != 0 and not (output / "refused.cpp").exists()
print("Hexagon source instrumentation: one post-success hook, transfer/fence exclusions, idempotency and changed-source refusal passed.")
if args.output:
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").write_text(json.dumps({
        "kind": "synthetic-host-api-and-pinned-source-boundaries",
        "upstream_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "probe_failure_isolation": True,
        "probe_single_attempt_concurrency": True,
        "admission_unknown_reserve_and_int64_boundaries": True,
        "instrumentation_post_success_non_copy_nodes": True,
        "instrumentation_changed_source_refused": True,
        "npu_executed": False,
        "android_executed": False,
    }, indent=2) + "\n")
