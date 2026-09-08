#!/usr/bin/env python3
"""Replay the compiled Gradle suites on explicit JVMs, recording assumption skips."""
import argparse, hashlib, json, os, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--jdk', type=Path, action='append', required=True)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
out = a.out.resolve(); out.mkdir(parents=True, exist_ok=True)
data = json.loads((root / 'build/mirai-test-classpaths.json').read_text())
source = out / 'MiraiTestReplay.java'
source.write_text('''import org.junit.runner.*;
import org.junit.runner.notification.*;
public class MiraiTestReplay {
 public static void main(String[] args) throws Exception {
  Class<?>[] classes=new Class<?>[args.length];
  for(int i=0;i<args.length;i++) classes[i]=Class.forName(args[i]);
  JUnitCore core=new JUnitCore(); final int[] assumptions={0};
  core.addListener(new RunListener(){ public void testAssumptionFailure(Failure f){assumptions[0]++;System.out.println("ASSUMPTION "+f);}});
  Result r=core.run(classes);
  for(Failure f:r.getFailures())System.out.println(f.getTrace());
  System.out.println("MIRAI_RESULT "+r.getRunCount()+" "+r.getIgnoreCount()+" "+assumptions[0]+" "+r.getFailureCount());
  if(!r.wasSuccessful())System.exit(1);
 }
}''', encoding='utf-8')
junit = next(Path(v) for v in data[':core']['runtimeClasspath'] if Path(v).name == 'junit-4.13.2.jar')
subprocess.run([str(a.jdk[0]/'bin/javac'), '-source', '8', '-target', '8', '-cp', str(junit),
                '-d', str(out), str(source)], check=True)
report = {'scope': __doc__, 'runs': [], 'dependencies': {}, 'compiled_tests': {}}
for module in ('core','api'):
    suites = [ET.parse(f).getroot() for f in sorted((root / 'coreSubProjects' / module / 'build/test-results/test').glob('TEST-*.xml'))]
    assert suites, 'Missing Gradle test XML: ' + module
    names = sorted(s.attrib['name'] for s in suites)
    expected = sum(int(s.attrib['tests']) for s in suites)
    cp = [out, *map(Path, data[':'+module]['runtimeClasspath'])]
    for f in cp:
        if f.is_file(): report['dependencies'][str(f)] = hashlib.sha256(f.read_bytes()).hexdigest()
    for directory in data[':'+module]['testClassesDirs']:
        for f in Path(directory).rglob('*.class'):
            report['compiled_tests'][str(f)] = hashlib.sha256(f.read_bytes()).hexdigest()
    # A manifest classpath avoids Windows process argument limits; use portable file URIs.
    uris = [f.resolve().as_uri() + ('/' if f.is_dir() else '') for f in cp if f.exists()]
    line = 'Class-Path: ' + ' '.join(uris)
    folded = []
    while len(line) > 70:
        folded.append(line[:70]); line = ' ' + line[70:]
    folded.append(line)
    cpjar = out / (module + '-classpath.jar')
    with zipfile.ZipFile(cpjar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n' + '\r\n'.join(folded) + '\r\n\r\n')
    for index, jdk in enumerate(a.jdk):
        version = subprocess.run([str(jdk/'bin/java'), '-version'], capture_output=True, text=True)
        cmd = [str(jdk/'bin/java'), '-Xverify:all', '-Xmx4096m', '-Djava.awt.headless=true',
               '-cp', str(cpjar), 'MiraiTestReplay', *names]
        log = out / (module + '-jvm' + str(index) + '.log')
        with log.open('w', encoding='utf-8') as stream:
            run = subprocess.run(cmd, cwd=root/'coreSubProjects'/module, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
        import re
        match = re.search(r'MIRAI_RESULT (\d+) (\d+) (\d+) (\d+)', log.read_text(encoding='utf-8', errors='replace'))
        counts = list(map(int, match.groups())) if match else None
        passed = run.returncode == 0 and counts is not None and counts[0] + counts[1] == expected and counts[3] == 0
        report['runs'].append({'module': module, 'jdk': str(jdk), 'version': version.stdout+version.stderr,
            'command': cmd, 'expected_tests': expected, 'counts_run_ignored_assumptions_failures': counts,
            'passed': passed, 'exit_code': run.returncode, 'log': str(log)})
        (out/'report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        print(module, jdk.name, 'PASS' if passed else 'FAIL', counts, flush=True)
report['passed'] = all(r['passed'] for r in report['runs'])
(out/'report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
raise SystemExit(0 if report['passed'] else 1)
