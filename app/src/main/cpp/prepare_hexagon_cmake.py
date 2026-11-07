#!/usr/bin/env python3
"""Use separately built HTP assets with the pinned Android host backend.

Only the extracted build dependency's CMake file changes. Its source archive
remains pinned. Android's CMake 3.22.1 builds the ARM64 host adapter; host CMake
3.31.6 builds the five DSP binaries once, before either APK variant is built.
"""
from pathlib import Path
import hashlib
import sys

target = Path(sys.argv[1])
text = target.read_text(encoding='utf-8')
base_sha256 = '30146e16cf331487276c32fbc343b91f454580b8fd9b52c0ad042d461cb26438'
output_sha256 = 'dd88f73f4ab61a043fd63927b659d42bd94a8ecfdb88d13e730858de4f96c3c2'
marker = '# SUBTITLE_PREBUILT_HTP_ASSETS_V1'
start = '# Build HTP skels\n'
if marker in text:
    if text.count(marker) != 1 or hashlib.sha256(text.encode()).hexdigest() != output_sha256:
        raise SystemExit('Ambiguous pinned Hexagon CMake adaptation')
elif text.count(start) == 1:
    if hashlib.sha256(text.encode()).hexdigest() != base_sha256:
        raise SystemExit('Pinned Hexagon CMake source bytes changed')
    original = text[text.index(start):]
    expected = ('build_htp_skel(v73)', 'build_htp_skel(v75)', 'build_htp_skel(v79)',
                'build_htp_skel(v81)', 'build_htp_skel(v85)', 'install(FILES ${HTP_SKELS} TYPE LIB)')
    if not all(original.count(anchor) == 1 for anchor in expected):
        raise SystemExit('Pinned Hexagon HTP build anchors changed')
    text = text[:text.index(start)] + marker + '''
# The verified DSP binaries are packaged as assets, never Android ABI ELF files.
# tools/prepare-hexagon.py owns their pinned toolchain and deterministic build.
'''
    if hashlib.sha256(text.encode()).hexdigest() != output_sha256:
        raise SystemExit('Pinned Hexagon CMake output bytes differ')
    target.write_text(text, encoding='utf-8')
else:
    raise SystemExit('Pinned Hexagon HTP build anchor is missing')
