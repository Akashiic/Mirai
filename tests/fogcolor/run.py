#!/usr/bin/env python3
"""Headless shipped-method regression: real FogState, explicit GL context fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--old-jar', required=True, type=Path)
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--angelica', action='append', required=True, type=Path)
    parser.add_argument('--joml', required=True, type=Path)
    parser.add_argument('--gtnhlib', required=True, type=Path)
    parser.add_argument('--asm', required=True, type=Path)
    parser.add_argument('--jdk8', required=True, type=Path)
    parser.add_argument('--jdk21', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    jars = [args.old_jar, args.joml, args.gtnhlib, args.asm, *args.angelica]
    if args.candidate:
        jars.append(args.candidate)
    results = {
        'scope': 'Final-JAR getFogColor instructions extracted without rewriting; real Angelica FogState and JOML. '
                 'Only GLStateManager context selection is a fixture. No Minecraft, native GL, GPU, '
                 'full GLStateManager initialization or mixin loading.',
        'artifacts': [{'path': str(path.resolve()), 'sha256': sha256(path)} for path in jars],
        'sources': [{'name': path.name, 'sha256': sha256(path)} for path in sorted(source.glob('*.java'))],
        'runtime_versions': {},
        'cases': [],
    }
    jdks = [('java8', args.jdk8.resolve()), ('java21', args.jdk21.resolve())]
    for label, jdk in jdks:
        version = subprocess.run([str(jdk / 'bin/java'), '-version'], text=True, capture_output=True, check=True)
        results['runtime_versions'][label] = version.stdout + version.stderr
    for index, angelica in enumerate(args.angelica):
        label = f'angelica-{index}-{angelica.stem}'
        work = output / label
        classes = work / 'classes'
        classes.mkdir(parents=True, exist_ok=True)
        deps = os.pathsep.join(str(path.resolve()) for path in [args.asm, angelica, args.joml, args.gtnhlib])
        command = [str(args.jdk8.resolve() / 'bin/javac'), '-source', '8', '-target', '8',
                   '-cp', deps, '-d', str(classes), *map(str, sorted(source.glob('*.java')))]
        compiled = subprocess.run(command, text=True, capture_output=True)
        (work / 'compile.log').write_text(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            results['cases'].append({'angelica': str(angelica), 'phase': 'compile', 'passed': False,
                                     'returncode': compiled.returncode, 'log': str(work / 'compile.log')})
            continue
        for runtime, jdk in jdks:
            candidates = [('negative', args.old_jar)]
            if args.candidate:
                candidates.append(('positive', args.candidate))
            for mode, jar in candidates:
                name = f'{runtime}-{mode}'
                classpath = os.pathsep.join([str(classes), deps])
                extracted = work / f'{name}-AngelicaAccessor.class'
                command = [str(jdk / 'bin/java'), '-Xverify:all', '-Djava.awt.headless=true',
                           '-cp', classpath, 'FogColorProbe', str(jar.resolve()), mode, str(extracted)]
                result = subprocess.run(command, text=True, capture_output=True)
                log = work / f'{name}.log'
                log.write_text(result.stdout + result.stderr)
                values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
                count = int(values.get('PASS_COUNT', '0'))
                expected = 1 if mode == 'negative' else 8
                case = {'angelica': str(angelica.resolve()), 'runtime': runtime, 'mode': mode,
                        'jar': str(jar.resolve()), 'command': command, 'returncode': result.returncode,
                        'passed': result.returncode == 0 and count == expected, 'checks_passed': count,
                        'checks_expected': expected, 'log': str(log),
                        'original_accessor_sha256': values.get('original_accessor_sha256'),
                        'extracted_fixture_sha256': values.get('extracted_fixture_sha256')}
                results['cases'].append(case)
                print(f"{label} {name}: {'PASS' if case['passed'] else 'FAIL'} ({count}/{expected})", flush=True)
    results['passed'] = bool(results['cases']) and all(case['passed'] for case in results['cases'])
    results['checks_passed'] = sum(case.get('checks_passed', 0) for case in results['cases'])
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    return 0 if results['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
