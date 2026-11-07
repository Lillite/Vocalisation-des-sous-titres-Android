#!/usr/bin/env python3
"""Qualify pinned patches, compile representative shaders and test ownership counters.

No Vulkan device or driver is executed. Successful GLSL compilation and host
tests are deliberately separate from numerical tests on the physical GPU.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("vulkan_compat", ROOT / "prepare_vulkan_compat.py")
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, required=True)
parser.add_argument("--glslc", type=Path)
parser.add_argument("--output", type=Path)
ARGS = parser.parse_args()
SOURCE = ARGS.source.resolve(strict=True)


def fixture(directory: Path, shaders: bool = False) -> Path:
    if shaders:
        path = Path("ggml/src/ggml-vulkan/vulkan-shaders")
        shutil.copytree(SOURCE / path, directory / path, dirs_exist_ok=True)
    for path in compat.load_manifest()["baseFileSha256"]:
        target = directory / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(SOURCE / path, target)
    return directory


class QualificationTests(unittest.TestCase):
    def test_all_pinned_changes_are_qualified_and_profile_is_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            root = fixture(Path(d))
            value = compat.prepare(root)
            text = (root / "ggml/src/ggml-vulkan/ggml-vulkan.cpp").read_text()
            self.assertFalse(value["defaultEnabled"])
            self.assertEqual(value["unconditionalMergedFix"], 30487)
            self.assertEqual(text.count("subtitle_qualcomm_compatibility_profile() &&"), 4)
            self.assertIn('std::strcmp(value, "1") == 0', text)
            self.assertIn("dequant_kv_sz <= ctx->device->max_buffer_size", text)
            self.assertIn("dequant_kv_sz <= ctx->device->max_memory_allocation_size", text)
            header = (root / "ggml/src/ggml-vulkan/ggml-vulkan-types.h").read_text()
            self.assertLess(header.index("device->device.freeMemory(device_memory)"),
                            header.index("subtitle_vulkan_buffer_released(subtitle_allocation_bytes"))

    def test_repeated_preparation_is_byte_identical(self):
        with tempfile.TemporaryDirectory() as d:
            root = fixture(Path(d))
            first = compat.prepare(root)
            self.assertEqual(compat.prepare(root), first)

    def test_unexpected_base_content_is_refused_before_other_files_change(self):
        with tempfile.TemporaryDirectory() as d:
            root = fixture(Path(d))
            bad = root / "ggml/src/ggml-vulkan/ggml-vulkan.cpp"
            good = root / "ggml/src/ggml-vulkan/vulkan-shaders/rms_norm.comp"
            before = good.read_bytes()
            bad.write_bytes(bad.read_bytes() + b"\n// unqualified change\n")
            with self.assertRaisesRegex(ValueError, "source content differs"):
                compat.prepare(root)
            self.assertEqual(good.read_bytes(), before)
            self.assertFalse((root / "subtitle-vulkan-compat.json").exists())

    def test_mutation_after_preparation_is_not_accepted_as_a_cache_hit(self):
        with tempfile.TemporaryDirectory() as d:
            root = fixture(Path(d))
            compat.prepare(root)
            bad = root / "ggml/src/ggml-vulkan/vulkan-shaders/types.glsl"
            bad.write_bytes(bad.read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "Previously prepared"):
                compat.prepare(root)
            with self.assertRaisesRegex(ValueError, "independently modified"):
                compat.requalify(root, SOURCE)

    def test_requalification_preserves_identical_shader_mtimes(self):
        with tempfile.TemporaryDirectory() as d:
            root = fixture(Path(d))
            compat.prepare(root)
            path = root / "ggml/src/ggml-vulkan/vulkan-shaders/types.glsl"
            before = path.read_bytes()
            timestamp = path.stat().st_mtime_ns
            receipt = root / "subtitle-vulkan-compat.json"
            value = json.loads(receipt.read_text())
            value["recipeSha256"] = "0" * 64
            receipt.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "provenance differs"):
                compat.prepare(root)
            qualified = compat.requalify(root, SOURCE)
            self.assertEqual(path.read_bytes(), before)
            self.assertEqual(path.stat().st_mtime_ns, timestamp)
            self.assertNotEqual(qualified["recipeSha256"], "0" * 64)

    def test_unqualified_patch_bytes_are_refused(self):
        original = compat.PATCH_DIRECTORY
        with tempfile.TemporaryDirectory() as d:
            target = Path(d)
            shutil.copytree(original, target, dirs_exist_ok=True)
            bad = target / "30821.patch"
            bad.write_bytes(bad.read_bytes() + b"\n")
            compat.PATCH_DIRECTORY = target
            try:
                with self.assertRaisesRegex(ValueError, "patch content differs"):
                    compat.load_manifest()
            finally:
                compat.PATCH_DIRECTORY = original

    def test_unlisted_or_ambiguous_patch_targets_are_refused(self):
        with self.assertRaisesRegex(ValueError, "Unlisted"):
            compat.apply_patch({"good": "old\n"}, "+++ b/../bad\n@@ -1 +1 @@\n-old\n+new\n")
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            compat.apply_patch({"good": "old\nold\n"}, "+++ b/good\n@@ -1 +1 @@\n-old\n+new\n")

    def test_four_open_proposals_remain_distinct_from_the_merged_fix(self):
        proposals = compat.load_manifest()["patches"]
        self.assertTrue(all(p["state"] == "open" and not p["merged"] for p in proposals[:4]))
        self.assertEqual([p["draft"] for p in proposals[:4]], [True, True, True, False])
        self.assertTrue(proposals[-1]["merged"])
        self.assertEqual(proposals[-1]["mergeCommitSha"], "3b83d12b0876f2eaef38c708ac40b15c6e1c7b3f")

    def test_integer_pack_unpack_equivalence_and_shared_table_range(self):
        # Algebraic conversion checks, not a claim about an Adreno compiler.
        def pack_old(values):
            return sum((v % 256) << (8 * i) for i, v in enumerate(values))
        def pack_new(values):
            return ((values[0] & 255) | ((values[1] & 255) << 8) |
                    ((values[2] & 255) << 16) | ((values[3] & 0xffffffff) << 24)) & 0xffffffff
        def unpack_signed(word):
            values = [(word >> (8 * i)) & 255 for i in range(4)]
            return [v - 256 if v & 128 else v for v in values]
        for position in range(4):
            for byte in range(256):
                values = [-128, -1, 0, 127]
                values[position] = byte - 256 if byte >= 128 else byte
                self.assertEqual(pack_old(values), pack_new(values))
                self.assertEqual(unpack_signed(pack_new(values)), values)
        state = 0x12345678
        for _ in range(10000):
            state = (state * 1664525 + 1013904223) & 0xffffffff
            values = unpack_signed(state)
            self.assertEqual(pack_new(values), state)
        source = (SOURCE / "ggml/src/ggml-vulkan/vulkan-shaders/types.glsl").read_text()
        for name in ("kvalues_iq4nl_const", "kvalues_mxfp4_const"):
            body = re.search(r"const int8_t " + name + r"\[16\] = \{(.*?)\};", source, re.S)
            self.assertIsNotNone(body)
            values = [int(v) for v in re.findall(r"int8_t\((-?\d+)\)", body.group(1))]
            self.assertEqual(len(values), 16)
            self.assertTrue(all(-128 <= v <= 127 for v in values))

    def test_profile_latch_and_allocation_ownership_are_real_host_code(self):
        code = r'''
#include "vulkan_profile_latch.h"
#include "vulkan_memory_telemetry.h"
#include <cassert>
#include <atomic>
#include <thread>
#include <vector>
int main() {
    subtitle_native::VulkanProfileLatch latch;
    int configurations = 0;
    assert(!latch.accept(true, [&](bool) { ++configurations; return false; }));
    assert(latch.accept(true, [&](bool value) { ++configurations; return value; }));
    assert(latch.accept(true, [&](bool) { assert(false); return true; }));
    assert(!latch.accept(false, [&](bool) { assert(false); return true; }));
    assert(configurations == 2);
    subtitle_native::VulkanProfileLatch concurrent;
    std::atomic<int> calls{0}, accepted{0};
    std::vector<std::thread> threads;
    for (int i = 0; i < 16; ++i) threads.emplace_back([&] {
        if (concurrent.accept(false, [&](bool) { ++calls; return true; })) ++accepted;
    });
    for (auto & t : threads) t.join();
    assert(calls == 1 && accepted == 16);
    subtitle_native::VulkanAllocationCounters counters;
    assert(!counters.snapshot().observed);
    counters.allocated(4096, false, true);
    counters.allocated(8192, true, true);
    auto both = counters.snapshot();
    assert(both.allocated_bytes == 12288 && both.peak_allocated_bytes == 12288);
    assert(both.allocations == 2 && both.largest_allocation_bytes == 8192);
    assert(both.imported_bytes == 8192 && both.host_visible_bytes == 12288);
    counters.failed();
    counters.released(8192, true, true);
    auto one = counters.snapshot();
    assert(one.allocated_bytes == 4096 && one.imported_bytes == 0 && one.host_visible_bytes == 4096);
    assert(one.allocation_failures == 1 && one.peak_allocated_bytes == 12288);
    counters.released(4096, false, true);
    assert(counters.snapshot().allocated_bytes == 0);
    threads.clear();
    for (int i = 0; i < 16; ++i) threads.emplace_back([&] {
        for (int j = 0; j < 1000; ++j) { counters.allocated(64, false, true); counters.released(64, false, true); }
    });
    for (auto & t : threads) t.join();
    assert(counters.snapshot().allocated_bytes == 0 && counters.snapshot().host_visible_bytes == 0);
    assert(counters.snapshot().allocations == 16002);
}
'''
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "test.cpp").write_text(code)
            subprocess.run(["c++", "-std=c++17", "-O2", "-pthread", "-I", str(ROOT),
                            str(root / "test.cpp"), "-o", str(root / "test")], check=True)
            subprocess.run([str(root / "test")], check=True)

    def test_ext_budget_is_separate_from_heap_capacity_and_stale_samples(self):
        code = r'''
#include "vulkan_memory_telemetry.h"
#include <cassert>
int main() {
    subtitle_native::VulkanBudgetCache cache;
    subtitle_native::VulkanMemorySnapshot sample;
    const uint32_t indices[] = {0, 2};
    const uint64_t budgets[] = {1024, 512}, usages[] = {256, 1024}, free[] = {768, 0};
    cache.observed(0, true, 2, indices, budgets, usages, free);
    cache.merge(0, sample);
    assert(sample.budget_observed && sample.budget_extension && sample.budget_heap_count == 2);
    assert(sample.budget_bytes == 1536 && sample.heap_usage_bytes == 1280 && sample.budget_free_bytes == 768);
    assert(sample.budget_heaps[1].index == 2 && sample.budget_heaps[1].free_bytes == 0);
    cache.invalidate(0);
    cache.merge(0, sample);
    assert(!sample.budget_observed && sample.budget_bytes == 0);
    cache.observed(0, false, 0, nullptr, nullptr, nullptr, nullptr);
    cache.merge(0, sample);
    assert(sample.budget_observed && !sample.budget_extension && sample.budget_free_bytes == 0);
    const uint32_t duplicate[] = {0, 0};
    cache.observed(0, true, 2, duplicate, budgets, usages, free);
    cache.merge(0, sample);
    assert(!sample.budget_observed && sample.budget_bytes == 0);
    cache.observed(0, true, 17, indices, budgets, usages, free);
    cache.merge(0, sample);
    assert(!sample.budget_observed);
    cache.observed(1, true, 2, indices, budgets, usages, free);
    cache.merge(1, sample);
    assert(sample.budget_observed && sample.budget_free_bytes == 768);
    cache.merge(0, sample);
    assert(!sample.budget_observed && sample.budget_free_bytes == 0);
}
'''
        self.compile_host(code)

    def test_partial_map_bind_and_address_failures_release_once(self):
        code = r'''
#include "vulkan_buffer_transaction.h"
#include "vulkan_memory_telemetry.h"
#include <cassert>
#include <stdexcept>
struct Resources { int buffers = 0, memory = 0, mappings = 0; };
struct Owner {
    Resources & r;
    subtitle_native::VulkanAllocationCounters & counters;
    bool buffer = false, memory = false, mapped = false, counted = false;
    unsigned size = 0;
    ~Owner() {
        if (!size) return;
        if (mapped) --r.mappings;
        if (memory) --r.memory;
        if (buffer) --r.buffers;
        if (counted) counters.released(size, false, true);
    }
};
void attempt(int fault, Resources & r, subtitle_native::VulkanAllocationCounters & counters) {
    Owner owner{r, counters};
    owner.buffer = true; ++r.buffers;
    subtitle_native::VulkanBufferTransaction transaction([&]() noexcept {
        if (owner.mapped) --r.mappings;
        if (owner.memory) --r.memory;
        if (owner.buffer) --r.buffers;
        owner.mapped = owner.memory = owner.buffer = false;
        owner.size = 0;
    });
    if (fault == 0) throw std::runtime_error("allocation");
    owner.memory = true; ++r.memory;
    if (fault == 1) throw std::runtime_error("map");
    owner.mapped = true; ++r.mappings;
    if (fault == 2) throw std::runtime_error("bind");
    owner.size = 4096;
    if (fault == 3) throw std::runtime_error("address");
    transaction.commit();
    owner.counted = true;
    counters.allocated(owner.size, false, true);
}
int main() {
    Resources resources;
    subtitle_native::VulkanAllocationCounters counters;
    for (int fault = 0; fault < 4; ++fault) {
        try { attempt(fault, resources, counters); assert(false); }
        catch (const std::runtime_error &) { }
        assert(resources.buffers == 0 && resources.memory == 0 && resources.mappings == 0);
        assert(counters.snapshot().allocated_bytes == 0 && counters.snapshot().allocations == 0);
    }
    attempt(4, resources, counters);
    assert(resources.buffers == 0 && resources.memory == 0 && resources.mappings == 0);
    assert(counters.snapshot().allocated_bytes == 0 && counters.snapshot().allocations == 1);
}
'''
        self.compile_host(code)

    def compile_host(self, code: str) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "test.cpp").write_text(code)
            subprocess.run(["c++", "-std=c++17", "-O2", "-pthread", "-I", str(ROOT),
                            str(root / "test.cpp"), "-o", str(root / "test")], check=True)
            subprocess.run([str(root / "test")], check=True)


def compile_shaders(glslc: Path, output: Path) -> list[dict]:
    root = fixture(output / "qualified-source", shaders=True)
    compat.prepare(root)
    shaders = root / "ggml/src/ggml-vulkan/vulkan-shaders"
    base = {"FLOAT_TYPE": "float", "FLOAT_TYPEV2": "vec2", "FLOAT_TYPEV4": "vec4",
            "B_TYPE": "float", "B_TYPEV4": "vec4", "D_TYPE": "float"}
    rms = base | {"A_TYPE": "float"}
    fa = base | {"ACC_TYPE": "float", "ACC_TYPEV2": "vec2", "ACC_TYPEV4": "vec4",
                 "DATA_A_IQ4_NL": "1", "Q_TYPE": "float", "D_TYPEV4": "vec4"}
    cases = [
        ("q6-k-matvec", "mul_mat_vec_q6_k.comp", base | {"DATA_A_Q6_K": "1"}, 63),
        ("rms", "rms_norm.comp", rms, 4),
        ("rms-add", "rms_norm.comp", rms | {"RMS_NORM_ADD_FUSION": "1"}, 4),
        ("rms-rope", "rms_norm.comp", rms | {"RMS_NORM_ROPE_FUSION": "1", "ROPE_D_TYPE": "float"}, 4),
        ("quantize-q8", "quantize_q8_1.comp", base, 63),
        ("scalar-fa", "flash_attn.comp", fa, 63),
    ]
    results = []
    for name, source, defines, expected_spec_id in cases:
        target = output / (name + ".spv")
        command = [str(glslc.resolve(strict=True)), "-fshader-stage=compute", "--target-env=vulkan1.2"]
        if "rope" not in name:
            command.append("-O")
        command.extend([str(shaders / source), "-o", str(target)])
        command.extend("-D" + key + "=" + value for key, value in defines.items())
        subprocess.run(command, check=True, capture_output=True)
        data = target.read_bytes()
        words = struct.unpack("<" + "I" * (len(data) // 4), data)
        if words[0] != 0x07230203:
            raise ValueError("Shader compiler did not produce SPIR-V")
        spec_ids = set()
        index = 5
        while index < len(words):
            length, opcode = words[index] >> 16, words[index] & 0xffff
            if length < 1 or index + length > len(words):
                raise ValueError("Invalid SPIR-V instruction")
            if opcode == 71 and length == 4 and words[index + 2] == 1:
                spec_ids.add(words[index + 3])
            index += length
        if expected_spec_id not in spec_ids:
            raise ValueError("The compiled shader lost its explicit compatibility specialization")
        results.append({"name": name, "size": len(data), "sha256": compat.digest(data),
                        "compatibilitySpecId": expected_spec_id})
    return results


def main() -> None:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(QualificationTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    shaders = []
    if ARGS.glslc:
        if ARGS.output:
            ARGS.output.mkdir(parents=True, exist_ok=True)
            shaders = compile_shaders(ARGS.glslc, ARGS.output)
        else:
            with tempfile.TemporaryDirectory(prefix="subtitle-vulkan-shaders-") as d:
                shaders = compile_shaders(ARGS.glslc, Path(d))
    summary = {"hostTests": result.testsRun, "shaderCompilations": shaders,
               "physicalGpuExecuted": False, "qualcommNumericalCorrectnessEstablished": False}
    if ARGS.output:
        (ARGS.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"hostTests": result.testsRun, "shaderCompilations": len(shaders),
                      "physicalGpuExecuted": False}))


if __name__ == "__main__":
    main()
