#!/usr/bin/env python3
"""Build pinned Hexagon DSP assets; never download anything on the phone.

The SDK is a build-time dependency. Only our compiled ggml HTP libraries and
their bounded integrity manifest become APK assets, never the vendor driver.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

SOURCE_REVISION = '8ca4060b7e30e76baaa5670ee1ec1b760b7f8552'
SOURCE_SHA256 = '68f2759b1cd93f352ade2413c1e123199632a348c46414b05f30efc67913f618'
SDK_VERSION = '6.7.0.0'
SDK_METADATA_VERSION = '6.7.0.1'
SDK_SHA256 = '00725fc449916d7b9af2c2a3c6b54d61f3c872d5dbc1d3ca3fd2f839b1d5dc70'
SDK_SIZE = 741882016
SDK_URL = ('https://github.com/snapdragon-toolchain/hexagon-sdk/releases/download/'
           'v6.7.0.0/hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz')
CMAKE_VERSION = '3.31.6'
CMAKE_SHA256 = '1c8b05df0602365da91ee6a3336fe57525b137706c4ab5675498f662ae1dbcec'
CMAKE_SIZE = 27800904
CMAKE_URL = ('https://files.pythonhosted.org/packages/59/e8/'
             '096984b89133681533650b9078c5ed1c5c9b534e869b5487f22d4de1935c/'
             'cmake-3.31.6-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl')
ARCHES = ('v73', 'v75', 'v79', 'v81', 'v85')
NAMES = tuple(f'libggml-htp-{arch}.so' for arch in ARCHES)
MAX_SKEL_BYTES = 32 * 1024 * 1024


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download(url: str, target: Path, expected: str, size: int | None = None) -> None:
    if target.is_file() and (size is None or target.stat().st_size == size) and digest(target) == expected:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.partial')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'SousTitresVoix-build/0.5'})
        with urllib.request.urlopen(request, timeout=120) as source, temporary.open('wb') as sink:
            shutil.copyfileobj(source, sink, 1024 * 1024)
        if size is not None and temporary.stat().st_size != size:
            raise ValueError('Pinned dependency size differs')
        if digest(temporary) != expected:
            raise ValueError('Pinned dependency SHA-256 differs')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def extract_tar(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        # Reject traversal and special files. Ignore archive ownership, including
        # on a root-in-container executor with user namespace mappings.
        source.extractall(destination, filter='data')


def verify_skel(path: Path) -> dict:
    if path.name not in NAMES or path.is_symlink() or not path.is_file():
        raise ValueError('Unexpected Hexagon library path')
    size = path.stat().st_size
    if not 52 <= size <= MAX_SKEL_BYTES:
        raise ValueError('Invalid Hexagon library size')
    with path.open('rb') as stream:
        header = stream.read(52)
    # HTP is a 32-bit little-endian Hexagon ELF, not an Android ARM64 library.
    if header[:7] != b'\x7fELF\x01\x01\x01' or struct.unpack_from('<H', header, 18)[0] != 164:
        raise ValueError('Expected a genuine EM_HEXAGON ELF32 library')
    if struct.unpack_from('<H', header, 16)[0] != 3:
        raise ValueError('Expected ET_DYN Hexagon library')
    return {'name': path.name, 'size': size, 'sha256': digest(path)}


def verify_assets(assets_root: Path) -> dict:
    directory = assets_root / 'hexagon'
    manifest_path = directory / 'manifest.json'
    if manifest_path.stat().st_size > 16384:
        raise ValueError('Hexagon manifest is oversized')
    value = json.loads(manifest_path.read_text(encoding='utf-8'))
    if set(value) != {'version', 'sourceRevision', 'files'} or type(value['version']) is not int or value['version'] != 1 or value['sourceRevision'] != SOURCE_REVISION:
        raise ValueError('Hexagon manifest identity differs')
    if not isinstance(value['files'], list) or len(value['files']) != len(NAMES):
        raise ValueError('Missing Hexagon architectures')
    measured = [verify_skel(directory / name) for name in NAMES]
    if value['files'] != measured:
        raise ValueError('Hexagon library hash, size or order differs')
    if {p.name for p in directory.iterdir()} != set(NAMES) | {'manifest.json'}:
        raise ValueError('Unexpected file in Hexagon asset directory')
    return value


def run(command: list[str], environment: dict[str, str]) -> None:
    subprocess.run(command, check=True, env=environment)


def prepare(args: argparse.Namespace) -> None:
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    sdk = args.sdk.resolve() if args.sdk else work / 'sdk' / SDK_VERSION
    if not (sdk / 'hexagon_sdk.json').is_file():
        archive = args.sdk_archive.resolve() if args.sdk_archive else work / 'downloads' / 'sdk.tar.xz'
        if args.sdk_archive:
            if archive.stat().st_size != SDK_SIZE or digest(archive) != SDK_SHA256:
                raise ValueError('Provided SDK archive does not match the pinned SDK')
        else:
            download(SDK_URL, archive, SDK_SHA256, SDK_SIZE)
        extract_tar(archive, sdk.parent)
    sdk_identity = json.loads((sdk / 'hexagon_sdk.json').read_text(encoding='utf-8'))
    if sdk_identity.get('root', {}).get('id', {}).get('version') != SDK_METADATA_VERSION:
        raise ValueError('SDK internal version differs from the pinned release content')
    tools = sdk / 'tools' / 'HEXAGON_Tools' / '21.0.04'
    for required in (sdk / 'build/cmake/hexagon_fun.cmake', tools / 'Tools/bin/hexagon-clang'):
        if not required.is_file():
            raise ValueError(f'Pinned SDK component is missing: {required.name}')

    if args.source:
        source = args.source.resolve()
    else:
        source = work / 'source' / ('llama.cpp-' + SOURCE_REVISION)
        if not source.is_dir():
            archive = work / 'downloads' / 'llama.tar.gz'
            download('https://codeload.github.com/ggml-org/llama.cpp/tar.gz/' + SOURCE_REVISION,
                     archive, SOURCE_SHA256)
            extract_tar(archive, source.parent)

    if args.cmake:
        cmake = args.cmake.resolve()
    else:
        cmake = work / 'host-cmake' / 'cmake/data/bin/cmake'
        if not cmake.is_file():
            wheel = work / 'downloads' / 'cmake.whl'
            download(CMAKE_URL, wheel, CMAKE_SHA256, CMAKE_SIZE)
            with zipfile.ZipFile(wheel) as archive:
                archive.extractall(work / 'host-cmake')
            for executable in (cmake, cmake.with_name('ctest'), cmake.with_name('cpack')):
                executable.chmod(0o755)
    version = subprocess.check_output([str(cmake), '--version'], text=True).splitlines()[0]
    if version != 'cmake version ' + CMAKE_VERSION:
        raise ValueError('HTP builds require pinned host CMake ' + CMAKE_VERSION)

    assets = args.assets.resolve()
    environment = os.environ.copy()
    environment.update(SOURCE_DATE_EPOCH='0', TZ='UTC', LC_ALL='C', PYTHONHASHSEED='0')
    build_root = work / 'htp-build'
    mappings = ((source, 'subtitle-llama'), (sdk, 'subtitle-hexagon-sdk'),
                (build_root, 'subtitle-hexagon-build'))
    prefix_flags = ' '.join(f'-ffile-prefix-map={path}={replacement}' for path, replacement in mappings)
    stamp_value = {'sourceRevision': SOURCE_REVISION, 'sdkSha256': SDK_SHA256,
                   'cmakeVersion': CMAKE_VERSION, 'buildPolicy': 1}
    stamp = work / 'build-receipt.json'
    cached = False
    if stamp.is_file() and json.loads(stamp.read_text()) == stamp_value:
        try:
            verify_assets(assets)
            cached = True
        except (OSError, ValueError):
            pass
    if not cached:
        (assets / 'hexagon').mkdir(parents=True, exist_ok=True)
        for arch in ARCHES:
            build = build_root / arch
            command = [str(cmake), '-S', str(source / 'ggml/src/ggml-hexagon/htp'), '-B', str(build),
                       '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
                       '-DCMAKE_TOOLCHAIN_FILE=' + str(source / 'ggml/src/ggml-hexagon/htp/cmake-toolchain.cmake'),
                       '-DHEXAGON_SDK_ROOT=' + str(sdk), '-DHEXAGON_TOOLS_ROOT=' + str(tools),
                       '-DDSP_VERSION=' + arch, '-DPREBUILT_LIB_DIR=toolv21_' + arch,
                       '-DPython3_EXECUTABLE=' + sys.executable,
                       '-DXQF_ARGS=' + prefix_flags]
            if args.ninja:
                command.append('-DCMAKE_MAKE_PROGRAM=' + str(args.ninja.resolve()))
            run(command, environment)
            run([str(cmake), '--build', str(build), '--parallel', str(args.jobs)], environment)
            built = build / ('libggml-htp-' + arch + '.so')
            verify_skel(built)
            shutil.copyfile(built, assets / 'hexagon' / built.name)
        value = {'version': 1, 'sourceRevision': SOURCE_REVISION,
                 'files': [verify_skel(assets / 'hexagon' / name) for name in NAMES]}
        (assets / 'hexagon/manifest.json').write_text(json.dumps(value, separators=(',', ':')) + '\n', encoding='utf-8')
        verify_assets(assets)
        stamp.write_text(json.dumps(stamp_value, sort_keys=True) + '\n', encoding='utf-8')
    # Preserve upstream attribution and the SDK toolchain's supplied notices.
    # This is not a claim that the complete Qualcomm SDK is redistributable.
    notices = assets / 'licenses'
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'LICENSE', notices / 'llama-cpp-MIT.txt')
    shutil.copyfile(tools / 'NOTICE.txt', notices / 'hexagon-tools-NOTICE.txt')
    values = {'SUBTITLE_HEXAGON_SDK_ROOT': str(sdk), 'SUBTITLE_HEXAGON_TOOLS_ROOT': str(tools),
              'SUBTITLE_HEXAGON_ASSETS_ROOT': str(assets)}
    if args.github_env:
        with args.github_env.open('a', encoding='utf-8') as stream:
            for name, value in values.items():
                if '\n' in value or '\r' in value:
                    raise ValueError('Invalid environment path')
                stream.write(name + '=' + value + '\n')
    print(json.dumps({'cached': cached, 'assets': str(assets), 'sdkVersion': SDK_VERSION,
                      'sdkMetadataVersion': SDK_METADATA_VERSION,
                      'sdkSha256': SDK_SHA256, 'sourceRevision': SOURCE_REVISION,
                      'files': verify_assets(assets)['files']}, sort_keys=True))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--sdk', type=Path)
    parser.add_argument('--sdk-archive', type=Path)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--cmake', type=Path)
    parser.add_argument('--ninja', type=Path)
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--github-env', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.verify_only:
        print(json.dumps(verify_assets(args.assets.resolve()), sort_keys=True))
    else:
        if args.work is None or not 1 <= args.jobs <= 32:
            parser.error('--work and --jobs between 1 and 32 are required')
        prepare(args)


if __name__ == '__main__':
    main()
