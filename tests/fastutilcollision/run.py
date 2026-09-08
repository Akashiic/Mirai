#!/usr/bin/env python3
"""Reproduce the reported ABI collision using the actual distribution JARs."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
from urllib.parse import urlsplit
from urllib.request import url2pathname
import zipfile

FASTUTIL_8518_SHA256 = '9094ae67d01d0ad246f886f11ad557fc2e79c72cbf3feed83e1512a8ae90a74a'

def sha256(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()

def origin_matches(output, key, expected_path):
    """Compare file locations, including Java's file:/C:/ form on Windows."""
    values = [line[len(key) + 1:] for line in output.splitlines() if line.startswith(key + '=')]
    if len(values) != 1:
        return False
    origin = urlsplit(values[0])
    if origin.scheme != 'file' or origin.query or origin.fragment:
        return False
    file_path = ('//' + origin.netloc if origin.netloc else '') + origin.path
    return pathlib.Path(url2pathname(file_path)).resolve() == expected_path.resolve()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=pathlib.Path, required=True)
    parser.add_argument('--candidate', type=pathlib.Path)
    parser.add_argument('--fastutil', type=pathlib.Path, required=True)
    parser.add_argument('--jdk8', type=pathlib.Path, required=True)
    parser.add_argument('--jdk21', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    for key in ('baseline', 'fastutil', 'jdk8', 'jdk21', 'candidate'):
        value = getattr(args, key)
        if value is not None:
            setattr(args, key, value.resolve(strict=True))
    if sha256(args.fastutil) != FASTUTIL_8518_SHA256:
        raise SystemExit('Wrong fastutil binary: expected pinned Maven Central fastutil 8.5.18')
    classes = args.output / 'classes'
    classes.mkdir(exist_ok=True)
    source = pathlib.Path(__file__).with_name('FastutilCollisionProbe.java')
    compiled = subprocess.run([str(args.jdk8 / 'bin/javac'), '-Xlint:unchecked', '-d', str(classes), str(source)],
                              capture_output=True, text=True, timeout=60)
    (args.output / 'compile.log').write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise SystemExit('Probe compilation failed; see compile.log')
    cases = [
        ('modern-only', 'external', [args.fastutil], False, args.fastutil),
        ('baseline-first', 'external', [args.baseline, args.fastutil], True, args.baseline),
        ('baseline-last', 'external', [args.fastutil, args.baseline], False, args.fastutil),
    ]
    if args.candidate:
        cases += [
            ('candidate-first', 'external', [args.candidate, args.fastutil], False, args.fastutil),
            ('candidate-last', 'external', [args.fastutil, args.candidate], False, args.fastutil),
            ('candidate-private-alone', 'isolated', [args.candidate], False, args.candidate),
            ('candidate-private-first', 'isolated', [args.candidate, args.fastutil], False, args.candidate),
            ('candidate-private-last', 'isolated', [args.fastutil, args.candidate], False, args.candidate),
        ]
    report = {
        'scope': 'Actual JAR ABI/classloader tests; no Minecraft/Forge/Angelica rendering initialization or OpenGL context',
        'inputs': {key: {'path': str(getattr(args, key)), 'sha256': sha256(getattr(args, key))}
                   for key in ('baseline', 'fastutil', 'candidate') if getattr(args, key)},
        'results': [],
        'packaging': None,
    }
    for name, jdk in [('java8', args.jdk8), ('java21', args.jdk21)]:
        version = subprocess.run([str(jdk/'bin/java'), '-version'], capture_output=True, text=True, timeout=15)
        report[name] = version.stdout + version.stderr
        for case, mode, jars, expected_failure, expected_origin in cases:
            command = [str(jdk/'bin/java'), '-Xverify:all', '-cp', str(classes),
                       'FastutilCollisionProbe', mode] + [str(jar) for jar in jars]
            try:
                run = subprocess.run(command, capture_output=True, text=True, timeout=30)
                output = run.stdout + run.stderr
                passed = origin_matches(output, 'ARRAY_ORIGIN', expected_origin)
                if expected_failure:
                    passed &= run.returncode != 0 and 'NoSuchMethodError' in output and 'ObjectArrayList.combine(' in output
                else:
                    passed &= run.returncode == 0 and 'PASS ' + mode in output
                    if mode == 'external':
                        passed &= origin_matches(output, 'IMMUTABLE_ORIGIN', args.fastutil)
                    else:
                        passed &= origin_matches(output, 'LONG_ARRAY_ORIGIN', args.candidate)
                        if len(jars) > 1:
                            for phase in ('BEFORE', 'AFTER'):
                                for kind in ('ARRAY', 'IMMUTABLE'):
                                    passed &= origin_matches(output, 'COEXIST_' + phase + '_' + kind + '_ORIGIN', args.fastutil)
                result = {'runtime': name, 'case': case, 'expected_collision': expected_failure,
                          'exit_code': run.returncode, 'passed': bool(passed), 'command': command}
            except subprocess.TimeoutExpired as exc:
                output = str(exc)
                result = {'runtime': name, 'case': case, 'passed': False, 'timeout': True, 'command': command}
            (args.output / (name + '-' + case + '.log')).write_text(output)
            report['results'].append(result)
            print(name, case, 'PASS' if result['passed'] else 'FAIL')
    if args.candidate:
        with zipfile.ZipFile(args.candidate) as jar:
            names = jar.namelist()
            unisolated = [n for n in names if n.startswith('it/unimi/dsi/fastutil/')]
            private = [n for n in names if n.startswith('DistantHorizons/libraries/unimi/dsi/fastutil/') and n.endswith('.class')]
            report['packaging'] = {'unisolated_entries': len(unisolated), 'private_classes': len(private),
                                   'passed': not unisolated and len(private) > 0}
    report['passed'] = all(r['passed'] for r in report['results']) and (not args.candidate or report['packaging']['passed'])
    report['count'] = len(report['results'])
    report['candidate_tested'] = args.candidate is not None
    (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
    return 0 if report['passed'] else 1

if __name__ == '__main__':
    sys.exit(main())
