#!/usr/bin/env python3
"""Verify native ABIs, actual ELF alignment and optional vendor GPU loading."""
from pathlib import Path
import os
import re
import subprocess
import sys
import tempfile
import zipfile

if len(sys.argv) != 2:
    raise SystemExit('Usage: check-native-apk.py application.apk')
sdk = Path(os.environ['ANDROID_HOME'])
readelf = sdk / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
with zipfile.ZipFile(sys.argv[1]) as archive, tempfile.TemporaryDirectory() as work:
    libraries = [name for name in archive.namelist() if re.fullmatch(r'lib/[^/]+/[^/]+\.so', name)]
    required = {f'lib/{abi}/libsubtitle_model_probe.so' for abi in ('arm64-v8a', 'x86_64')}
    assert required.issubset(libraries), 'Both tested diagnostic native ABIs must be packaged'
    assert {name.split('/')[1] for name in libraries} == {'arm64-v8a', 'x86_64'}
    assert all(Path(name).name != 'libOpenCL.so' for name in libraries), 'Do not bundle a vendor OpenCL driver'
    for name in sorted(libraries):
        local = Path(work) / 'library.so'
        local.write_bytes(archive.read(name))
        result = subprocess.run([str(readelf), '-lW', str(local)], check=True, text=True, capture_output=True)
        loads = [line.split() for line in result.stdout.splitlines() if line.strip().startswith('LOAD ')]
        assert loads, f'No ELF LOAD segment: {name}'
        assert all(int(fields[-1], 16) >= 16384 for fields in loads), f'ELF alignment below 16 KiB: {name}'
        dynamic = subprocess.run([str(readelf), '-dW', str(local)], check=True, text=True, capture_output=True)
        needed = re.findall(r'\(NEEDED\).*?\[([^\]]+)\]', dynamic.stdout)
        assert 'libOpenCL.so' not in needed, f'CPU execution must not require the optional vendor OpenCL library: {name}'
        print(f'PASS native ELF LOAD alignment >= 16 KiB: {name}')
        print(f'PASS vendor OpenCL absent from mandatory ELF dependencies: {name}')
