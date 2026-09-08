#!/usr/bin/env python3
"""Real Camera + DhMat4f + JOML; extracted 1.7.10 wrapper method, fixture MC lifecycle.

Compares against the exact 0.1.9 Camera source and injects world/entity changes.
This is not a Minecraft integration or image-rendering test.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = 'fa91834906f7d643b283e42ce2c5113e1dbdc2d2'
CAMERA = 'common/src/main/java/com/seibel/distanthorizons/common/backports/Camera.java'
WRAPPER = 'common/src/main/java/com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.java'


def method(source, signature):
    if source.count(signature) != 1:
        raise ValueError('Expected one production method signature: ' + signature)
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 0
    tokens = re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', source[brace:])
    for token in tokens:
        if token.group() == '{':
            depth += 1
        elif token.group() == '}':
            depth -= 1
            if depth == 0:
                return source[start:brace + token.end()]
    raise ValueError('Unbalanced production method')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--javac', required=True)
    ap.add_argument('--java', required=True)
    ap.add_argument('--joml', type=Path, required=True)
    args = ap.parse_args()
    src, classes = args.out / 'src', args.out / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    manifest = {'probe_runner': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}

    def write(pkg, name, text):
        path = src / Path(pkg.replace('.', '/')) / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def fixture(pkg, name, body):
        write(pkg, name, 'package ' + pkg + ';\n' + body)

    def copy_source(path):
        data = (ROOT / path).read_bytes()
        manifest[path] = hashlib.sha256(data).hexdigest()
        text = data.decode('utf-8')
        write(re.search(r'package ([\w.]+);', text)[1], Path(path).stem,
              re.sub(r'^\s*#(?:if|endif).*$', '', text, flags=re.M))

    for path in [CAMERA,
                 'coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/math/DhMat4f.java',
                 'coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/objects/math/DhApiMat4f.java',
                 'coreSubProjects/api/src/main/java/com/seibel/distanthorizons/api/interfaces/util/IDhApiCopyable.java']:
        copy_source(path)
    baseline_file = Path(__file__).parent / 'camera-handoff-fixtures/Camera-0.1.9.java.txt'
    old = baseline_file.read_text(encoding='utf-8')
    manifest['baseline_camera'] = hashlib.sha256(old.encode()).hexdigest()
    if manifest['baseline_camera'] != '59a467f4a0572e57215a36921007e1750f7328fd8307e7f7c3682cf1788052e0':
        raise ValueError('Baseline Camera differs from the recorded 0.1.9 source')
    old = re.sub(r'\bCamera\b', 'LegacyCamera', old)
    write('com.seibel.distanthorizons.common.backports', 'LegacyCamera',
          re.sub(r'^\s*#(?:if|endif).*$', '', old, flags=re.M))

    fixture('net.minecraft.client.multiplayer', 'WorldClient', 'public class WorldClient {}')
    fixture('net.minecraft.entity', 'EntityLivingBase', '''public class EntityLivingBase {
        public net.minecraft.client.multiplayer.WorldClient worldObj;
        public double posX,posY,posZ,lastTickPosX,lastTickPosY,lastTickPosZ;
    }''')
    fixture('com.seibel.distanthorizons.core.util.math', 'DhVec3d', '''public class DhVec3d {
        public double x,y,z; public DhVec3d(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    }''')
    fixture('fixture', 'IClientLevelWrapper', '''public interface IClientLevelWrapper { Object getWrappedMcObject(); }''')
    fixture('com.seibel.distanthorizons.core.api.internal', 'ClientApi', '''public class ClientApi {
        public static final State RENDER_STATE = new State();
        public static class State { public com.seibel.distanthorizons.core.util.math.DhMat4f mcModelViewMatrix;
            public fixture.IClientLevelWrapper clientLevelWrapper; }
    }''')
    fixture('fixture', 'DelayedAccessors', '''public class DelayedAccessors {
        public static Portal IMMERSIVE_PORTALS;
        public interface Portal { com.seibel.distanthorizons.core.util.math.DhVec3d getActualCameraPos(); }
    }''')
    data = (ROOT / WRAPPER).read_bytes()
    manifest[WRAPPER] = hashlib.sha256(data).hexdigest()
    body = method(data.decode(), 'public DhVec3d getCameraPositionForLodTick(')
    body = re.sub(r'#else[\s\S]*?#endif', '', body)
    body = re.sub(r'^\s*#if.*$', '', body, flags=re.M)
    fixture('fixture', 'Wrapper', '''import com.seibel.distanthorizons.core.api.internal.ClientApi;
        import com.seibel.distanthorizons.core.util.math.DhVec3d;
        import com.seibel.distanthorizons.common.backports.Camera;
        public class Wrapper { public static final Mc MC = new Mc();
        public static class Mc { public net.minecraft.client.multiplayer.WorldClient theWorld;
            public net.minecraft.entity.EntityLivingBase renderViewEntity; public final Timer timer=new Timer(); }
        public static class Timer { public float renderPartialTicks; }
        ''' + body + '}')
    probe_body = Path(__file__).parent / 'camera-handoff-fixtures/CameraHandoffProbe.body'
    manifest['probe_fixture'] = hashlib.sha256(probe_body.read_bytes()).hexdigest()
    fixture('fixture', 'CameraHandoffProbe', probe_body.read_text(encoding='utf-8'))
    compile_run = subprocess.run([args.javac, '-source', '8', '-target', '8', '-cp', str(args.joml),
                                  '-d', str(classes)] + [str(p) for p in src.rglob('*.java')],
                                 capture_output=True, text=True, timeout=90)
    (args.out / 'compile.log').write_text(compile_run.stdout + compile_run.stderr)
    if compile_run.returncode:
        raise RuntimeError(compile_run.stderr)
    cp = os.pathsep.join([str(classes), str(args.joml)])
    run = subprocess.run([args.java, '-Xverify:all', '-cp', cp, 'fixture.CameraHandoffProbe'], capture_output=True, text=True, timeout=90)
    (args.out / 'run.log').write_text(run.stdout + run.stderr)
    version = subprocess.run([args.java, '-version'], capture_output=True, text=True, timeout=20)
    expected = 'PASS 12023 checks including 12000 baseline-exact matrices, 80000 concurrent snapshots and lifecycle injection'
    result = dict(passed=run.returncode == 0 and expected in run.stdout.splitlines(), stdout=run.stdout, stderr=run.stderr,
                  java=version.stderr, baseline_commit=BASELINE, source_sha256=manifest,
                  joml_sha256=hashlib.sha256(args.joml.read_bytes()).hexdigest(),
                  scope='Exact Camera + real DH matrix + JOML; extracted wrapper method; fixture lifecycle, no Minecraft')
    (args.out / 'results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
    if not result['passed']:
        raise SystemExit(run.returncode or 1)


if __name__ == '__main__':
    main()
