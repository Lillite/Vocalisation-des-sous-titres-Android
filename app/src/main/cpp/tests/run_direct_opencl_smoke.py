#!/usr/bin/env python3
"""Exercise actual direct C forwarding against host-only non-ICD libraries.

No Android, model inference or GPU execution is simulated or claimed here.
The production generator and pinned public headers compile the real wrappers.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys

HEADER_SHA256 = {
    'CL/cl.h': '0e695976bc3014c8376efc6436ef4dafce25deb74ddd7b1c8c07924444f293fc',
    'CL/cl_platform.h': 'be9647d121bfea20cfac9fe2be2e3ac7851a0dd77a141363dcc72f126a1a358c',
    'CL/cl_version.h': '1b161f73c0f0b07121314055f75df6c19d6a93e3a0d24202c6f3e2dd1615b2a2',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--headers', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    headers = args.headers.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    for name, expected in HEADER_SHA256.items():
        if hashlib.sha256((headers / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError('The host test requires the pinned production OpenCL headers.')
    compiler = shutil.which('g++')
    readelf = shutil.which('readelf')
    if not compiler or not readelf:
        raise RuntimeError('Actual host g++ and readelf are required; no stub fallback.')
    console = output / 'console.txt'
    cases = []
    with console.open('w') as log:
        def run(command):
            result = subprocess.run([str(x) for x in command], text=True, capture_output=True,
                                    timeout=120, check=False)
            log.write(result.stdout)
            log.write(result.stderr)
            log.flush()
            if result.returncode:
                raise RuntimeError('Actual direct-dispatch compilation or execution failed; see ' + str(console))
            return result.stdout

        generated = output / 'direct_opencl_generated.cpp'
        fake = output / 'fake_opencl.cpp'
        run([sys.executable, source / 'generate_direct_opencl.py', headers / 'CL/cl.h', generated])
        run([sys.executable, source / 'tests/generate_fake_opencl.py', headers / 'CL/cl.h', fake])
        include = ['-I' + str(source), '-I' + str(headers)]
        flags = ['-std=c++17', '-pthread', '-O2', '-Wall', '-Wextra', '-Werror']
        executable = output / 'direct-opencl-dispatch-smoke'
        run([compiler, *flags, '-fvisibility=hidden', *include, generated,
             source / 'tests/direct_opencl_dispatch_smoke.cpp', '-ldl', '-o', executable])
        for mode, defines in [('complete', []), ('optional', ['-DDIRECT_TEST_OPTIONAL=1']),
                              ('missing', ['-DDIRECT_TEST_MISSING_REQUIRED=1'])]:
            library = output / ('libsynthetic-direct-' + mode + '.so')
            run([compiler, *flags, '-Wno-unused-parameter', '-shared', '-fPIC', *defines,
                 *include, fake, '-o', library])
            run([executable, library, mode])
            cases.append(mode)
        diagnostic = output / 'optional-opencl-diagnostic-smoke'
        run([compiler, *flags, '-I' + str(source), source / 'tests/optional_opencl_diagnostic_smoke.cpp',
             '-o', diagnostic])
        run([diagnostic])
        dynamic = run([readelf, '--dyn-syms', '-W', executable])
        symbols = [line.split()[-1].split('@', 1)[0] for line in dynamic.splitlines() if line.split()]
        if any(re.fullmatch(r'cl[A-Z]\w*', value) for value in symbols):
            raise RuntimeError('OpenCL wrappers must not be exported or unresolved dynamic symbols.')
        if 'libOpenCL.so' in run([readelf, '-d', '-W', executable]):
            raise RuntimeError('The host wrapper must not have a mandatory vendor dependency.')
    summary = {
        'status': 'passed', 'syntheticHostDriverOnly': True, 'gpuExecutionValidated': False,
        'actualForwardingCases': cases, 'boundedProbeDiagnostics': True,
        'nonIcdOpaqueHandles': True, 'callbacksAnd64BitSizes': True,
        'atomicPublication': True, 'unpreparedCallsDoNotLoadDriver': True,
        'missingRequiredSymbolDoesNotPublishPartialTable': True,
        'dynamicOpenClExportsOrUndefinedSymbols': False,
        'mandatoryVendorDependency': False, 'pinnedHeaderSha256': HEADER_SHA256,
    }
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
