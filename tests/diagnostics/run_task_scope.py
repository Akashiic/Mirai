#!/usr/bin/env python3
"""Exercise the actual packaged render queue in isolation; no Minecraft/GL context is started."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, tempfile

p = argparse.ArgumentParser()
p.add_argument('jar', type=Path)
p.add_argument('--jdk', type=Path, action='append', required=True)
p.add_argument('--junit', type=Path, required=True)
p.add_argument('--hamcrest', type=Path, required=True)
p.add_argument('--log4j', type=Path, action='append', required=True, help='Explicit Log4j API and core runtime JARs')
p.add_argument('--negative-control', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
jar = a.jar.resolve()
baseline = a.negative_control.resolve()
result = {'jar': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
          'negative_control': str(baseline), 'negative_sha256': hashlib.sha256(baseline.read_bytes()).hexdigest(),
          'scope': 'Actual RenderThreadTaskHandler bytecode, headless tasks in an isolated classloader. No Minecraft/driver validation.', 'runs': []}
source = Path(__file__).resolve().parents[2] / 'coreSubProjects/core/src/test/java/com/seibel/distanthorizons/core/render/RenderTaskScopeTest.java'
with tempfile.TemporaryDirectory(prefix='mirai-task-scope-') as temp:
    dependencies = os.pathsep.join(str(path.resolve()) for path in (a.junit, a.hamcrest, *a.log4j))
    subprocess.run([str(a.jdk[0] / 'bin/javac'), '-source', '8', '-target', '8', '-cp', dependencies,
                    '-d', temp, str(source)], check=True)
    cp = os.pathsep.join((temp, str(jar), dependencies))
    for jdk in a.jdk:
        for negative in (False, True):
            command = [str(jdk / 'bin/java'), '-Xverify:all']
            if negative:
                command.append('-Dmirai.queue.test.baseline=' + str(baseline))
            command += ['-cp', cp, 'org.junit.runner.JUnitCore', 'com.seibel.distanthorizons.core.render.RenderTaskScopeTest']
            process = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
            if negative:
                passed = (process.returncode != 0 and 'Tests run: 2,  Failures: 2' in process.stdout
                          and 'Nested draining must restore the active outer scope' in process.stdout
                          and 'A failed task must not remain reported as running' in process.stdout)
            else:
                passed = process.returncode == 0 and 'OK (2 tests)' in process.stdout
            result['runs'].append({'jdk': str(jdk), 'negative_control': negative, 'passed': passed,
                                   'exit_code': process.returncode, 'stdout': process.stdout, 'stderr': process.stderr})
result['passed'] = all(run['passed'] for run in result['runs'])
a.report.parent.mkdir(parents=True, exist_ok=True)
a.report.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'passed': result['passed'], 'report': str(a.report)}))
raise SystemExit(0 if result['passed'] else 1)
