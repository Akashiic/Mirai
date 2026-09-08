#!/usr/bin/env python3
"""Real packaged queue; controlled client executor, no Minecraft/GL driver startup."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, tempfile

p = argparse.ArgumentParser()
p.add_argument('jar', type=Path)
p.add_argument('--jdk', type=Path, action='append', required=True)
p.add_argument('--junit', type=Path, required=True)
p.add_argument('--hamcrest', type=Path, required=True)
p.add_argument('--log4j', type=Path, action='append', required=True)
p.add_argument('--negative-control', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
sources = [root / 'coreSubProjects/core/src/test/java/com/seibel/distanthorizons/core/render' / name
           for name in ('RenderTaskScopeTest.java', 'RenderTaskMaintenanceTest.java')]
dependencies = [a.junit, a.hamcrest, *a.log4j]
result = {'scope': __doc__, 'artifacts': {str(f): hashlib.sha256(f.read_bytes()).hexdigest()
          for f in [a.jar, a.negative_control, *dependencies, *sources]}, 'runs': []}
with tempfile.TemporaryDirectory(prefix='mirai-maintenance-') as temp:
    cp_deps = os.pathsep.join(str(f.resolve()) for f in dependencies)
    subprocess.run([str(a.jdk[0] / 'bin/javac'), '-source', '8', '-target', '8', '-cp', cp_deps,
                    '-d', temp, *map(str, sources)], check=True)
    cp = os.pathsep.join([temp, str(a.jar.resolve()), cp_deps])
    for jdk in a.jdk:
        for negative in (False, True):
            cmd = [str(jdk / 'bin/java'), '-Xverify:all']
            if negative:
                cmd.append('-Dmirai.queue.test.baseline=' + str(a.negative_control.resolve()))
            cmd += ['-cp', cp, 'org.junit.runner.JUnitCore',
                    'com.seibel.distanthorizons.core.render.RenderTaskMaintenanceTest']
            run = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
            if negative:
                expected = ['A stalled executor must not accumulate cleanup callbacks',
                            "A stale callback must not drain the next frame's work",
                            'Maintenance must yield after a task exceeds the frame budget',
                            'Player builds should not allocate a stack per task by default',
                            'Tests run: 9,  Failures: 7']
                passed = run.returncode != 0 and all(s in run.stdout for s in expected)
            else:
                passed = run.returncode == 0 and 'OK (9 tests)' in run.stdout
            result['runs'].append({'command': cmd, 'negative': negative, 'passed': passed,
                                   'returncode': run.returncode, 'stdout': run.stdout, 'stderr': run.stderr})
result['passed'] = all(r['passed'] for r in result['runs'])
a.report.parent.mkdir(parents=True, exist_ok=True)
a.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'passed': result['passed'], 'report': str(a.report)}))
raise SystemExit(0 if result['passed'] else 1)
