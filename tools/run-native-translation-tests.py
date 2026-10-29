#!/usr/bin/env python3
"""Exercise real host inference; never claim phone/GPU speed or linguistic quality."""
import argparse
import json
import math
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
NUMBER = r'[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?'


def parse_logit_comparison(log):
    # The full stop belongs to the sentence, never the final numeric group.
    matches = re.findall(r'^Context-sensitive logits: cached-vs-fresh maximum error (' + NUMBER +
                         r'), changed-context delta (' + NUMBER + r')\.[ \t]*$', log, re.MULTILINE)
    if len(matches) != 1:
        raise RuntimeError('A unique real context-sensitive logit comparison was not reported')
    error, delta = map(float, matches[0])
    if not math.isfinite(error) or not math.isfinite(delta) or not 0 <= error < 0.00002 or not delta > 0.000001:
        raise RuntimeError('Cache equivalence or context sensitivity failed')
    return error, delta


def unique_dependency(name):
    matches = list((ROOT / 'app/.cxx/Debug').glob('*/arm64-v8a/_deps/' + name))
    if len(matches) != 1:
        raise RuntimeError('Expected the unique pinned Android debug dependency: ' + name)
    return matches[0].resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cmake', required=True, type=Path)
    parser.add_argument('--ninja', required=True, type=Path)
    parser.add_argument('--llama-source', type=Path)
    parser.add_argument('--opencl-headers', type=Path)
    parser.add_argument('--work-parent', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    work = Path(tempfile.mkdtemp(prefix='subtitle-native-', dir=args.work_parent))
    llama = args.llama_source.resolve() if args.llama_source else unique_dependency('probe_llama-src')
    headers = args.opencl_headers.resolve() if args.opencl_headers else unique_dependency('subtitle_opencl_headers-src')
    if not (llama / 'include/llama.h').is_file() or not (headers / 'CL/cl.h').is_file():
        raise RuntimeError('Pinned dependency sources are missing')
    steps = []

    def run(name, command, timeout):
        log = output / (name + '.log')
        start = time.monotonic()
        with log.open('w', encoding='utf-8') as stream:
            result = subprocess.run([str(part) for part in command], cwd=ROOT,
                                    stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        steps.append({'step': name, 'exitCode': result.returncode,
                      'elapsedMs': round((time.monotonic() - start) * 1000), 'log': log.name})
        if result.returncode:
            raise RuntimeError(name + ' failed; see ' + str(log))
        return log.read_text(encoding='utf-8', errors='replace')

    summary = {'status': 'running', 'realHostInference': True, 'gpuExecutionValidated': False,
               'phonePerformanceValidated': False, 'linguisticQualityValidated': False, 'steps': steps}
    try:
        run('configure', [args.cmake.resolve(), '-S', ROOT / 'app/src/main/cpp', '-B', work / 'build',
            '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_MAKE_PROGRAM=' + str(args.ninja.resolve()),
            '-DFETCHCONTENT_SOURCE_DIR_PROBE_LLAMA=' + str(llama),
            '-DFETCHCONTENT_SOURCE_DIR_SUBTITLE_OPENCL_HEADERS=' + str(headers)], 180)
        run('build', [args.cmake.resolve(), '--build', work / 'build', '--target',
            'local-translation-smoke', 'model-probe-smoke', 'optional-opencl-diagnostic-smoke', '--parallel', '2'], 600)
        generator = ROOT / 'app/src/main/cpp/tests/make_tiny_gguf.py'
        repeat, eos, context = (work / name for name in ('repeat.gguf', 'eos.gguf', 'context.gguf'))
        run('fixture-repeat', ['python3', generator, repeat], 30)
        run('fixture-eos', ['python3', generator, eos, '--terminating', '--roundtrip'], 30)
        run('fixture-context', ['python3', generator, context, '--terminating', '--context-sensitive', '--roundtrip'], 30)
        translation_log = run('translation', [work / 'build/local-translation-smoke', eos, repeat, context], 120)
        run('probe', [work / 'build/model-probe-smoke', repeat], 60)
        run('diagnostic', [work / 'build/optional-opencl-diagnostic-smoke'], 60)
        error, delta = parse_logit_comparison(translation_log)
        summary.update(status='passed', cachedVsFreshMaxLogitError=error, changedContextDelta=delta)
    except BaseException as error:
        summary.update(status='failed', failureType=type(error).__name__)
        raise
    finally:
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary, sort_keys=True))


if __name__ == '__main__':
    main()
