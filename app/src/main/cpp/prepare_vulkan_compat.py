#!/usr/bin/env python3
"""Qualify pinned upstream fixes before Vulkan shaders or host code are built.

The downloaded archive is unchanged. Only its extracted build copy is adapted.
Four still-open upstream proposals are gated by an explicit process-local profile;
the merged combined K/V allocation-limit fix is always applied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
PATCH_DIRECTORY = ROOT / "vulkan_compat"
ENVIRONMENT = "SUBTITLE_VULKAN_QUALCOMM_COMPAT"
MARKER = "// SUBTITLE_PINNED_VULKAN_COMPAT_V1"


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_manifest() -> dict:
    value = json.loads((PATCH_DIRECTORY / "provenance.json").read_text(encoding="utf-8"))
    if value.get("version") != 1 or value.get("sourceRevision") != "8ca4060b7e30e76baaa5670ee1ec1b760b7f8552":
        raise ValueError("Unexpected Vulkan compatibility source revision")
    if [p["number"] for p in value["patches"]] != [30821, 30824, 30826, 30829, 30487]:
        raise ValueError("Unexpected Vulkan compatibility patch set")
    for path in value["baseFileSha256"]:
        if not path.startswith("ggml/src/ggml-vulkan/") or ".." in Path(path).parts:
            raise ValueError("Unsafe compatibility source path")
    for patch in value["patches"]:
        if patch["file"] != str(patch["number"]) + ".patch":
            raise ValueError("Unsafe compatibility patch path")
        if digest((PATCH_DIRECTORY / patch["file"]).read_bytes()) != patch["sha256"]:
            raise ValueError("Pinned Vulkan patch content differs")
    return value


def apply_patch(files: dict[str, str], patch: str) -> None:
    """Apply context hunks uniquely; refuse drift instead of accepting fuzz."""
    lines = patch.splitlines(keepends=True)
    index = 0
    path = None
    while index < len(lines):
        line = lines[index]
        if line.startswith("+++ b/"):
            path = line[len("+++ b/"):].strip()
            if path not in files:
                raise ValueError("Unlisted Vulkan patch target")
            index += 1
        elif line.startswith("@@ "):
            if path is None:
                raise ValueError("Vulkan patch hunk has no target")
            if not re.match(r"@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@", line):
                raise ValueError("Invalid Vulkan patch hunk")
            old, new = [], []
            index += 1
            while index < len(lines) and not lines[index].startswith(("@@ ", "diff --git ")):
                part = lines[index]
                if part.startswith(" "):
                    old.append(part[1:]); new.append(part[1:])
                elif part.startswith("-"):
                    old.append(part[1:])
                elif part.startswith("+"):
                    new.append(part[1:])
                elif part.startswith("\\ No newline"):
                    raise ValueError("Unexpected unterminated Vulkan source")
                else:
                    raise ValueError("Unexpected Vulkan patch content")
                index += 1
            before, after = "".join(old), "".join(new)
            if not before or files[path].count(before) != 1:
                raise ValueError("Pinned Vulkan hunk context changed or is ambiguous")
            files[path] = files[path].replace(before, after, 1)
        else:
            index += 1


def replace_once(text: str, before: str, after: str) -> str:
    if text.count(before) != 1:
        raise ValueError("Pinned Vulkan compatibility adaptation anchor changed")
    return text.replace(before, after, 1)


def adapt_profile(files: dict[str, str]) -> None:
    path = "ggml/src/ggml-vulkan/ggml-vulkan.cpp"
    text = files[path]
    text = replace_once(text, '#include "ggml-vulkan-common.h"\n',
                        '#include "ggml-vulkan-common.h"\n#include <cstdlib>\n#include <cstring>\n')
    # The profile is latched by optional_vulkan_backend before this registry is
    # first opened. A later switch requires a new private model process.
    helper = MARKER + """
static bool subtitle_qualcomm_compatibility_profile() {
    const char * value = std::getenv("SUBTITLE_VULKAN_QUALCOMM_COMPAT");
    return value != nullptr && std::strcmp(value, "1") == 0;
}

"""
    signature = "static void ggml_vk_create_pipeline_func("
    if text.count(signature) != 1:
        raise ValueError("Pinned Vulkan pipeline function changed")
    text = text.replace(signature, helper + signature, 1)
    text = replace_once(text,
        'device->avoid_8bit_vectors = device->vendor_id == VK_VENDOR_ID_QUALCOMM ||\n                                     getenv("GGML_VK_AVOID_8BIT_VECTORS") != nullptr;',
        'device->avoid_8bit_vectors = subtitle_qualcomm_compatibility_profile() &&\n                                     device->vendor_id == VK_VENDOR_ID_QUALCOMM;')
    text = replace_once(text,
        "const uint32_t rms_unroll = device->driver_id != vk::DriverId::eQualcommProprietary;",
        "const uint32_t rms_unroll = !(subtitle_qualcomm_compatibility_profile() &&\n                                  device->driver_id == vk::DriverId::eQualcommProprietary);")
    text = replace_once(text,
        "// Workaround for subgroupAdd() returning wrong sums on Adreno after divergent code\n        if (device->driver_id == vk::DriverId::eQualcommProprietary) {",
        "// Explicit experimental workaround for subgroupAdd() on Qualcomm.\n        if (subtitle_qualcomm_compatibility_profile() &&\n            device->driver_id == vk::DriverId::eQualcommProprietary) {")
    text = replace_once(text,
        "if (device->vendor_id == VK_VENDOR_ID_QUALCOMM && (hsk > 128 || hsv > 128)) {",
        "if (subtitle_qualcomm_compatibility_profile() &&\n        device->vendor_id == VK_VENDOR_ID_QUALCOMM && (hsk > 128 || hsv > 128)) {")
    # Do not copy hardware-specific performance multipliers into project code.
    text = text.replace("    // 40x slower; at large KV a single dispatch can then exceed the Windows GPU timeout.\n",
                        "    // excessively; this profile is not a measured speedup on the phone.\n")
    files[path] = text


def add_memory_observation(files: dict[str, str]) -> None:
    path = "ggml/src/ggml-vulkan/ggml-vulkan.cpp"
    text = files[path]
    signature = "void ggml_backend_vk_get_device_memory(int device, size_t * free, size_t * total) {"
    if text.count(signature) != 1:
        raise ValueError("Pinned Vulkan memory-query signature changed")
    begin = text.index(signature)
    end = text.index("\n}", begin) + 2
    block = text[begin:end]
    block = replace_once(block, "    *free = 0;", """    *free = 0;
    uint32_t subtitle_heap_indices[16]{};
    uint64_t subtitle_heap_budgets[16]{}, subtitle_heap_usages[16]{}, subtitle_heap_free[16]{};
    uint32_t subtitle_heap_count = 0;
    bool subtitle_heaps_complete = true;""")
    anchor = "                *free += budget > budgetprops.heapUsage[i] ? budget - budgetprops.heapUsage[i] : 0;"
    block = replace_once(block, anchor, anchor + """
                if (subtitle_heap_count < 16) {
                    const uint32_t target = subtitle_heap_count++;
                    subtitle_heap_indices[target] = i;
                    subtitle_heap_budgets[target] = budget;
                    subtitle_heap_usages[target] = budgetprops.heapUsage[i];
                    subtitle_heap_free[target] = budget > budgetprops.heapUsage[i] ? budget - budgetprops.heapUsage[i] : 0;
                } else {
                    subtitle_heaps_complete = false;
                }""")
    block = block[:-2] + """
    // The application deliberately ignores the capacity-as-free fallback above.
    subtitle_vulkan_budget_observed(device, membudget_supported,
        subtitle_heaps_complete ? subtitle_heap_count : 17,
        subtitle_heap_indices, subtitle_heap_budgets, subtitle_heap_usages, subtitle_heap_free);
}"""
    declaration = """extern "C" void subtitle_vulkan_budget_observed(int device, bool extension, uint32_t count,
    const uint32_t * indices, const uint64_t * budget, const uint64_t * usage, const uint64_t * free) noexcept;
"""
    files[path] = text[:begin] + declaration + block + text[end:]
    path = "ggml/src/ggml-vulkan/ggml-vulkan-types.h"
    text = files[path]
    text = replace_once(text, "struct vk_buffer_struct {", """extern "C" void subtitle_vulkan_buffer_allocated(uint64_t bytes, bool imported, bool host_visible) noexcept;
extern "C" void subtitle_vulkan_buffer_released(uint64_t bytes, bool imported, bool host_visible) noexcept;
extern "C" void subtitle_vulkan_buffer_allocation_failed() noexcept;

struct vk_buffer_struct {
    uint64_t subtitle_allocation_bytes = 0;
    bool subtitle_imported = false;
    bool subtitle_host_visible = false;""")
    text = replace_once(text,
        "        device->device.freeMemory(device_memory);\n        device->device.destroyBuffer(buffer);",
        """        device->device.freeMemory(device_memory);
        device->device.destroyBuffer(buffer);
        if (subtitle_allocation_bytes != 0) {
            subtitle_vulkan_buffer_released(subtitle_allocation_bytes, subtitle_imported, subtitle_host_visible);
        }""")
    files[path] = text
    path = "ggml/src/ggml-vulkan/ggml-vulkan-buffers.cpp"
    text = files[path]
    text = replace_once(text, '#include "ggml-vulkan-common.h"\n',
                        '#include "ggml-vulkan-common.h"\n#include "vulkan_buffer_transaction.h"\n')
    signature = "static vk_buffer ggml_vk_create_buffer(vk_device& device, size_t size, const std::initializer_list<vk::MemoryPropertyFlags> & req_flags_list,"
    if text.count(signature) != 1:
        raise ValueError("Pinned Vulkan allocator signature changed")
    begin = text.index(signature)
    end = text.index("\n}", begin) + 2
    block = text[begin:end]
    # Before ownership is committed, the original shared owner's size is zero.
    # Without a transaction, failed mapping/binding can leak that partial object.
    old_cleanup = "device->device.destroyBuffer(buf->buffer);"
    if block.count(old_cleanup) != 4:
        raise ValueError("Pinned Vulkan partial-allocation cleanup anchors changed")
    block = re.sub(r"^[ ]*" + re.escape(old_cleanup) + r"\n", "", block, flags=re.M)
    block = replace_once(block, "    vk_buffer buf = std::make_shared<vk_buffer_struct>();",
                         "    vk_buffer buf = std::make_shared<vk_buffer_struct>();\n    buf->ptr = nullptr;")
    anchor = "    buf->buffer = device->device.createBuffer(buffer_create_info);"
    block = replace_once(block, anchor, anchor + """
    subtitle_native::VulkanBufferTransaction transaction([&]() noexcept {
        if (buf->device_memory) {
            if (buf->ptr && !import_ptr) device->device.unmapMemory(buf->device_memory);
            device->device.freeMemory(buf->device_memory);
        }
        if (buf->buffer) device->device.destroyBuffer(buf->buffer);
        buf->device_memory = VK_NULL_HANDLE;
        buf->buffer = VK_NULL_HANDLE;
        buf->ptr = nullptr;
        buf->size = 0;
        buf->device = nullptr;
    });""")
    # Once committed, the existing shared owner alone frees the completed object.
    anchor = "    device->memory_logger->log_allocation(buf, size);"
    block = replace_once(block, anchor, "    transaction.commit();\n" + anchor)
    text = text[:begin] + block + text[end:]
    # Track a complete buffer object's real allocation size, not every shared
    # pointer reset. Imported pages are separately tagged to avoid summing them
    # with the same host residency. Allocator failures do not change live bytes.
    text = replace_once(text, "    device->memory_logger->log_allocation(buf, size);", """    buf->subtitle_allocation_bytes = import_ptr ? size : mem_req.size;
    buf->subtitle_imported = import_ptr != nullptr;
    buf->subtitle_host_visible = bool(buf->memory_property_flags & vk::MemoryPropertyFlagBits::eHostVisible);
    subtitle_vulkan_buffer_allocated(buf->subtitle_allocation_bytes, buf->subtitle_imported, buf->subtitle_host_visible);
    device->memory_logger->log_allocation(buf, size);""")
    # These two exported creation helpers each catch an allocation exception.
    # They call the common private allocator directly, never each other.
    anchors = [
        '        std::cerr << "ggml_vulkan: Memory allocation of size " << size << " failed." << std::endl;',
        '        std::cerr << "ggml_vulkan: Device memory allocation of size " << size << " failed." << std::endl;',
    ]
    for anchor in anchors:
        text = replace_once(text, anchor, "        subtitle_vulkan_buffer_allocation_failed();\n" + anchor)
    files[path] = text


def prepare(source: Path) -> dict:
    source = source.resolve(strict=True)
    manifest = load_manifest()
    paths = manifest["baseFileSha256"]
    receipt_path = source / "subtitle-vulkan-compat.json"
    payload = {path: (source / path).read_bytes() for path in paths}
    if receipt_path.is_file():
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        expected_identity = digest((PATCH_DIRECTORY / "provenance.json").read_bytes())
        if receipt.get("manifestSha256") != expected_identity or receipt.get("recipeSha256") != digest(Path(__file__).read_bytes()):
            raise ValueError("Vulkan compatibility provenance differs; use a fresh dependency tree")
        if receipt.get("outputFileSha256") != {path: digest(data) for path, data in payload.items()}:
            raise ValueError("Previously prepared Vulkan sources changed")
        return receipt
    for path, expected in paths.items():
        if digest(payload[path]) != expected:
            raise ValueError("Pinned Vulkan source content differs: " + path)
    files = {path: data.decode("utf-8") for path, data in payload.items()}
    for patch in manifest["patches"]:
        apply_patch(files, (PATCH_DIRECTORY / patch["file"]).read_text(encoding="utf-8"))
    adapt_profile(files)
    add_memory_observation(files)
    output = {path: text.encode("utf-8") for path, text in files.items()}
    receipt = {
        "version": 1, "sourceRevision": manifest["sourceRevision"],
        "manifestSha256": digest((PATCH_DIRECTORY / "provenance.json").read_bytes()),
        "recipeSha256": digest(Path(__file__).read_bytes()),
        "profileEnvironment": ENVIRONMENT,
        "defaultEnabled": False,
        "unconditionalMergedFix": 30487,
        "outputFileSha256": {path: digest(data) for path, data in output.items()},
    }
    # All transforms and hashes complete before any build input is replaced.
    for path, data in output.items():
        if (source / path).read_bytes() != data:
            (source / path).write_bytes(data)
    receipt_path.write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return receipt


def requalify(source: Path, pristine: Path) -> dict:
    """Update an owned build copy, preserving mtimes for unchanged shader bytes."""
    source, pristine = source.resolve(strict=True), pristine.resolve(strict=True)
    if source == pristine:
        raise ValueError("The pristine source must not be the build copy")
    manifest = load_manifest()
    receipt_path = source / "subtitle-vulkan-compat.json"
    receipt_bytes = receipt_path.read_bytes()
    previous = json.loads(receipt_bytes)
    paths = manifest["baseFileSha256"]
    if previous.get("outputFileSha256") != {path: digest((source / path).read_bytes()) for path in paths}:
        raise ValueError("Refusing to overwrite an independently modified build copy")
    original = {path: (pristine / path).read_bytes() for path in paths}
    if {path: digest(data) for path, data in original.items()} != paths:
        raise ValueError("Pristine Vulkan source does not match the pinned base")
    before = {path: ((source / path).read_bytes(), (source / path).stat()) for path in paths}
    try:
        for path, data in original.items():
            (source / path).write_bytes(data)
        receipt_path.unlink()
        result = prepare(source)
        for path, (data, stat) in before.items():
            if (source / path).read_bytes() == data:
                os.utime(source / path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        return result
    except BaseException:
        for path, (data, stat) in before.items():
            (source / path).write_bytes(data)
            os.utime(source / path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        receipt_path.write_bytes(receipt_bytes)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--restore-from", type=Path,
                        help="Requalify a verified owned build copy from a pristine pinned tree")
    args = parser.parse_args()
    receipt = requalify(args.source, args.restore_from) if args.restore_from else prepare(args.source)
    print("Prepared pinned Vulkan compatibility: 4 opt-in proposals, 1 merged allocation-limit fix; "
          + str(len(receipt["outputFileSha256"])) + " source files verified.")


if __name__ == "__main__":
    main()
