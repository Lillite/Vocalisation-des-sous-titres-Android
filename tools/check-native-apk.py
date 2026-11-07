#!/usr/bin/env python3
"""Verify native ABIs, actual ELF alignment and optional vendor GPU loading."""
from pathlib import Path
import importlib.util
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
    spec = importlib.util.spec_from_file_location('hexagon_build', Path(__file__).with_name('prepare-hexagon.py'))
    hexagon = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(hexagon)
    dsp_assets = {name for name in archive.namelist() if name.startswith('assets/hexagon/') and not name.endswith('/')}
    expected_assets = {'assets/hexagon/' + name for name in hexagon.NAMES} | {'assets/hexagon/manifest.json'}
    assert dsp_assets == expected_assets, 'Package the five genuine DSP assets and their integrity manifest'
    asset_root = Path(work) / 'assets'
    (asset_root / 'hexagon').mkdir(parents=True)
    for name in sorted(dsp_assets):
        maximum = 16384 if name.endswith('manifest.json') else hexagon.MAX_SKEL_BYTES
        assert archive.getinfo(name).file_size <= maximum, f'Oversized DSP asset: {name}'
        (asset_root / 'hexagon' / Path(name).name).write_bytes(archive.read(name))
    hexagon.verify_assets(asset_root)
    for name in ('llama-cpp-MIT.txt', 'hexagon-tools-NOTICE.txt'):
        assert 'assets/licenses/' + name in archive.namelist(), f'Missing third-party attribution: {name}'
    print('PASS five genuine Hexagon DSP ELF32 assets, pinned manifest and content integrity')
    libraries = [name for name in archive.namelist() if re.fullmatch(r'lib/[^/]+/[^/]+\.so', name)]
    required = {f'lib/{abi}/libsubtitle_model_probe.so' for abi in ('arm64-v8a', 'x86_64')}
    assert required.issubset(libraries), 'Both tested diagnostic native ABIs must be packaged'
    assert {name.split('/')[1] for name in libraries} == {'arm64-v8a', 'x86_64'}
    assert all(Path(name).name != 'libOpenCL.so' for name in libraries), 'Do not bundle a vendor OpenCL driver'
    assert all(Path(name).name != 'libvulkan.so' for name in libraries), 'Do not bundle a vendor Vulkan loader'
    assert all(Path(name).name != 'libcdsprpc.so' for name in libraries), 'Do not bundle a vendor FastRPC driver'
    assert all(not Path(name).name.startswith('libggml-htp-') for name in libraries), 'DSP ELFs must never masquerade as Android ABI libraries'
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
        assert 'libvulkan.so' not in needed, f'CPU execution must not require the optional Vulkan loader: {name}'
        assert 'libcdsprpc.so' not in needed, f'CPU execution must not require the optional FastRPC driver: {name}'
        if Path(name).name == 'libsubtitle_model_probe.so':
            symbols = subprocess.run([str(readelf), '--dyn-syms', '-W', str(local)],
                                     check=True, text=True, capture_output=True)
            opencl_symbols = [line.split() for line in symbols.stdout.splitlines()
                              if len(line.split()) >= 8 and
                              re.fullmatch(r'cl[A-Z][A-Za-z0-9_]*(?:@[^ ]+)?', line.split()[7])]
            assert not opencl_symbols, f'OpenCL forwarding must stay private and have no unresolved vendor APIs: {name}'
            vulkan_symbols = [line.split() for line in symbols.stdout.splitlines()
                              if len(line.split()) >= 8 and
                              re.fullmatch(r'vk[A-Z][A-Za-z0-9_]*(?:@[^ ]+)?', line.split()[7])]
            assert not vulkan_symbols, f'Vulkan forwarding must stay private and have no unresolved loader APIs: {name}'
            print(f'PASS OpenCL API wrappers absent from exported/unresolved dynamic symbols: {name}')
            print(f'PASS Vulkan API wrappers absent from exported/unresolved dynamic symbols: {name}')
        print(f'PASS native ELF LOAD alignment >= 16 KiB: {name}')
        print(f'PASS vendor OpenCL absent from mandatory ELF dependencies: {name}')
        print(f'PASS Vulkan loader absent from mandatory ELF dependencies: {name}')
