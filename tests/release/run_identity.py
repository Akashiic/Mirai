#!/usr/bin/env python3
"""Run the identity parser from the final JAR on specified JVMs (no Minecraft)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('jar', type=Path)
p.add_argument('--jdk', action='append', type=Path, required=True)
p.add_argument('--log4j-api', type=Path, required=True)
p.add_argument('--expected-version', required=True)
p.add_argument('--report', type=Path, required=True)
args = p.parse_args()
jar = args.jar.resolve()
with zipfile.ZipFile(jar) as archive:
    info = json.loads(archive.read('build_info.json'))
report = {'jar': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'runs': [],
          'scope': 'Actual packaged metadata classes only; no Forge/OpenGL/game initialization.'}
with tempfile.TemporaryDirectory(prefix='mirai-identity-') as directory:
    source = Path(__file__).with_name('ReleaseIdentityProbe.java')
    subprocess.run([str(args.jdk[0] / 'bin/javac'), '-source', '8', '-target', '8',
                    '-d', directory, str(source)], check=True, capture_output=True, text=True)
    cp = os.pathsep.join([directory, str(jar), str(args.log4j_api.resolve())])
    for jdk in args.jdk:
        command = [str(jdk / 'bin/java'), '-Xverify:all', '-cp', cp, 'ReleaseIdentityProbe',
                   args.expected_version, info['info_git_commit'], info['info_git_branch'], info['info_build_source']]
        run = subprocess.run(command, capture_output=True, text=True, timeout=30)
        report['runs'].append({'jdk': str(jdk), 'exit_code': run.returncode,
                              'output': run.stdout + run.stderr})
report['passed'] = all(run['exit_code'] == 0 for run in report['runs'])
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
raise SystemExit(0 if report['passed'] else 1)
