#!/usr/bin/env python3
"""Run the diagnostic hook on the delivered JAR on JDK 8/21; this does not launch Minecraft."""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile
p = argparse.ArgumentParser()
p.add_argument('jar', type=Path)
p.add_argument('--jdk', type=Path, action='append', required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
jar = a.jar.resolve()
result = {'jar': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
          'scope': 'Modes: baseline counter/timing, resolved-method upload, and nested render/queue/task/upload/LOD timings. No event formatting, disk writes or full game snapshot on measured caller. Caller allocation measured after warmup. Isolated JVM microbenchmark, not FPS.', 'runs': []}
with tempfile.TemporaryDirectory(prefix='mirai-diag-probe-classes-') as temp:
    source = Path(__file__).with_name('DiagnosticHotPathProbe.java')
    subprocess.run([str(a.jdk[0] / 'bin/javac'), '-cp', str(jar), '-d', temp, str(source)], check=True)
    for jdk in a.jdk:
        for enabled in ('false', 'true'):
            for mode, mode_name in enumerate(('counter_timing', 'upload', 'all_timings')):
                cmd = [str(jdk / 'bin/java'), '-Xverify:all', '-cp', temp + ':' + str(jar), 'DiagnosticHotPathProbe', enabled, str(mode)]
                process = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
                rows = [json.loads(line) for line in process.stdout.splitlines() if line.startswith('{')]
                result['runs'].append({'jdk': str(jdk), 'enabled': enabled == 'true', 'mode': mode_name, 'exit_code': process.returncode,
                                       'passed': process.returncode == 0 and len(rows) == 5 and all(row['caller_allocated_bytes'] == 0 for row in rows),
                                       'rounds': rows, 'stderr': process.stderr})
result['passed'] = all(run['passed'] for run in result['runs'])
a.report.parent.mkdir(parents=True, exist_ok=True)
a.report.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'passed': result['passed'], 'report': str(a.report)}))
raise SystemExit(0 if result['passed'] else 1)
