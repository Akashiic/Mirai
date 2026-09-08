#!/usr/bin/env python3
"""Exercise exact production composition classes with explicit Java/GL doubles.

Java 8/21, no Minecraft or GPU. --baseline extracts pinned 0.1.3 sources into
the output directory and applies the same assertions; failures are required.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT.parent
OUT = WORK / 'homologation-0.1.4-evidence/rendering/composition-probes'
PREFIX = 'com/seibel/distanthorizons/'
FILES = [
 'coreSubProjects/core/src/main/java/' + PREFIX + 'core/render/renderer/LodRenderer.java',
 *['common/src/main/java/' + PREFIX + 'common/render/openGl/' + p for p in [
  'util/GlResourceCleanup.java', 'util/GlAbstractShaderRenderer.java',
  'glObject/shader/GlShader.java', 'glObject/shader/GlShaderProgram.java',
  'postProcessing/fade/GlDhFarFadeRenderer.java',
  'postProcessing/fade/GlDhFarFadeShader.java',
  'postProcessing/fade/GlDhFarFadeApplyShader.java']],
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', action='store_true')
    args = parser.parse_args()
    out = OUT / ('baseline-0.1.3' if args.baseline else 'candidate')
    src, classes = out / 'fixtures', out / 'classes'
    classes.mkdir(parents=True, exist_ok=True)

    def write(name, body):
        path = src / (name.replace('.', '/') + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body)

    write('com.seibel.distanthorizons.core.render.RenderBufferHandler', '''
public class RenderBufferHandler {
 public void buildRenderList(RenderParams p) { probe.State.events.add("sort"); }
 public com.seibel.distanthorizons.core.util.objects.SortedArraySet<com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer> getColumnRenderBuffers() {
  return new com.seibel.distanthorizons.core.util.objects.SortedArraySet<>((a,b)->0);
 }
}''')
    write('com.seibel.distanthorizons.core.render.renderer.FogRenderParamFactory', '''
public class FogRenderParamFactory {
 public static com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent.EventParam getRenderParam(com.seibel.distanthorizons.core.render.RenderParams p) {
  return new com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent.EventParam();
 }
}''')
    write('com.seibel.distanthorizons.core.util.RenderUtil', '''
public class RenderUtil { public static float getFarClipPlaneDistanceInBlocks() { return 1000; } }
''')
    write('com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider', '''
public class LWJGLServiceProvider {
 public static final ILWJGLService LWJGL = probe.Driver.create();
 public static final int POINTER_SIZE=8; public static final long NULL=0;
}''')
    write('com.seibel.distanthorizons.lwjgl.AbstractMemoryStack', '''
public class AbstractMemoryStack implements AutoCloseable {
 public static AbstractMemoryStack stackPush() { return new AbstractMemoryStack(); }
 public java.nio.FloatBuffer mallocFloat(int n) { return java.nio.FloatBuffer.allocate(n); }
 public void close() { }
}''')
    write('com.seibel.distanthorizons.common.render.openGl.glObject.GLState', '''
public class GLState implements AutoCloseable {
 public GLState() { probe.State.captures++; }
 public void close() { probe.State.restores++; if(probe.State.failRestore) throw probe.State.RESTORE; }
}''')
    write('com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer', '''
public class GlDhMetaRenderer {
 public static final GlDhMetaRenderer INSTANCE = new GlDhMetaRenderer();
 public static int depth=101,color=102,framebuffer=103;
 public int getActiveDepthTextureId() { return depth; }
 public int getActiveColorTextureId() { return color; }
 public int getActiveFramebufferId() { return framebuffer; }
}''')
    write('com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper', '''
import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;
public class MinecraftGLWrapper {
 public static final MinecraftGLWrapper INSTANCE = new MinecraftGLWrapper();
 public void glViewport(int x,int y,int w,int h) { LWJGL.glViewport(x,y,w,h); }
 public void glBindFramebuffer(int t,int id) { LWJGL.glBindFramebuffer(t,id); }
 public int glGenTextures() { return LWJGL.glGenTextures(); }
 public void glDeleteTextures(int id) { LWJGL.glDeleteTextures(id); }
 public void glBindTexture(int id) { LWJGL.glBindTexture(3553,id); }
 public void glActiveTexture(int id) { LWJGL.glActiveTexture(id); }
 public void disableScissorTest() { LWJGL.glDisable(3089); }
 public void disableDepthTest() { LWJGL.glDisable(2929); }
 public void enableDepthTest() { LWJGL.glEnable(2929); }
 public void disableBlend() { LWJGL.glDisable(3042); }
}''')
    write('com.seibel.distanthorizons.common.render.openGl.postProcessing.GlScreenQuad', '''
public class GlScreenQuad {
 public static final GlScreenQuad INSTANCE = new GlScreenQuad();
 public void render() { probe.State.quads++; if(probe.State.failQuad) throw probe.State.PRIMARY; }
}''')
    for path in sorted((ROOT / 'tests/rendering/composition-fixtures').rglob('*.java')):
        target = src / path.relative_to(ROOT / 'tests/rendering/composition-fixtures')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(path.read_bytes())

    jar = WORK / 'deliverables-0.1.3/Akashic-Horizons-of-The-Mirai-forge17-0.1.3-dev-1.7.10.jar'
    cp = ':'.join(map(str, [classes, ROOT / 'coreSubProjects/core/src/main/resources', jar,
        next((WORK / 'gradle-cache').rglob('lwjgl-2.9.1.jar')),
        next((WORK / 'gradle-cache').rglob('log4j-api-2.0-beta9.jar'))]))
    sources = []
    for filename in FILES:
        source = ROOT / filename
        if args.baseline:
            core = filename.startswith('coreSubProjects/')
            gitroot = ROOT / 'coreSubProjects' if core else ROOT
            revision = '43eab6cb293197bddbd0ace4622864e7594252a1' if core else '4ce7a1290c62fa9fea6197812b5b342aedf2effc'
            rel = filename.removeprefix('coreSubProjects/') if core else filename
            content = subprocess.run(['git', 'show', revision + ':' + rel], cwd=gitroot, capture_output=True, check=True).stdout
            source = out / 'production' / filename
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(content)
        sources.append(source)
    compile_run = subprocess.run([str(WORK/'toolchains/jdk8/bin/javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', cp, '-d', str(classes), *map(str, sources), *map(str, sorted(src.rglob('*.java')))], capture_output=True, text=True)
    (out / 'compile.log').write_text(compile_run.stdout + compile_run.stderr)
    if compile_run.returncode:
        print(compile_run.stderr)
        raise SystemExit(compile_run.returncode)
    results = []
    for jdk in ['jdk8', 'jdk21']:
        for probe, mode in [('LodPassProbe', 'absent'), ('LodPassProbe', 'present'), *[('FarFadeProbe', mode) for mode in ['all', 'shader-failure', 'missing-input', 'invalid-viewport', 'incomplete-fbo']]]:
            run = subprocess.run([str(WORK/'toolchains'/jdk/'bin/java'), '-Xverify:all', '-cp', cp, 'probe.'+probe, mode], capture_output=True, text=True)
            log = jdk + '-' + probe + '-' + mode + '.log'
            (out / log).write_text(run.stdout + run.stderr)
            print(jdk, probe, mode, run.returncode, run.stdout[-600:], run.stderr[-1000:])
            results.append({'jdk': jdk, 'probe': probe, 'mode': mode, 'exit_code': run.returncode, 'log': log})
    (out / 'results.json').write_text(json.dumps({'baseline': args.baseline, 'scope': 'Actual production classes; Java doubles for driver, wrappers, memory stack, GLState, quad, render buffers and fog factory; no GPU', 'sources': {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in zip(FILES, sources)}, 'results': results}, indent=2) + '\n')
    expected = [0, 1, 1, 1, 1, 1, 1]*2 if args.baseline else [0]*14
    if [r['exit_code'] for r in results] != expected:
        raise SystemExit('Unexpected probe results; inspect logs before approving.')
    if args.baseline:
        for result in results:
            if result['exit_code'] == 0:
                continue
            expected_message = ('exact render order expected' if result['probe'] == 'LodPassProbe'
                else 'intermediate framebuffer completeness checked' if result['mode'] == 'all'
                else 'Expected a failure; composition must not silently continue.')
            if expected_message not in (out / result['log']).read_text():
                raise SystemExit('Baseline failed for an unexpected reason: ' + result['log'])


if __name__ == '__main__':
    main()
