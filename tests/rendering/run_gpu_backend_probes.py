#!/usr/bin/env python3
"""Compile the unchanged GPU backend/interface against a strict native-call fixture.

This checks capability/context ownership and dispatch, not GPU execution. Five
intentional source mutations must fail the corresponding safety assertion.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BACKEND = Path('common/src/main/java/com/seibel/distanthorizons/common/render/openGl/MiraiGlGpuTimerBackend.java')
LWJGL = Path('common/src/lwjglCommon/java/com/seibel/distanthorizons/lwjgl')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--javac', required=True)
parser.add_argument('--java', required=True)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)

FIXTURES = {
'com/seibel/distanthorizons/core/logging/mirai/MiraiDiagnostics.java': '''package com.seibel.distanthorizons.core.logging.mirai;
public final class MiraiDiagnostics {
 public static final java.util.Map<String,String> values = new java.util.HashMap<String,String>();
 public static void metadata(String key, String value) { values.put(key, value); }
}
''',
'com/seibel/distanthorizons/lwjgl/LWJGLServiceProvider.java': '''package com.seibel.distanthorizons.lwjgl;
public final class LWJGLServiceProvider { public static ILWJGLService LWJGL; }
''',
'com/seibel/distanthorizons/core/logging/mirai/MiraiGpuProfiler.java': '''package com.seibel.distanthorizons.core.logging.mirai;
public final class MiraiGpuProfiler {
 public interface ContextBackend {
  int create(); void delete(int id); void timestamp(int id); boolean available(int id); long result(int id);
  boolean isCurrentContext(); int counterBits();
 }
}
''',
'GpuBackendProbe.java': r'''import com.seibel.distanthorizons.common.render.openGl.MiraiGlGpuTimerBackend;
import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.lwjgl.*;
import java.lang.reflect.*;
import java.util.*;
public class GpuBackendProbe implements InvocationHandler {
 static int checks;
 Object token = new Object(); boolean core = true, arb = false, ready = false, fail = false;
 boolean queryBufferBound;
 int bits = 64, next = 41; List<String> calls = new ArrayList<String>();
 public Object invoke(Object proxy, Method method, Object[] a) {
  String name = method.getName(); calls.add(name);
  if (name.equals("getCurrentContextToken")) return token;
  if (name.equals("isOpenGLVersionSupported")) { check(a[0].equals(3) && a[1].equals(3), "version_target"); return core; }
  if (name.equals("isExtensionSupported")) { check(a[0] == EGLExtension.ARB_timer_query, "extension_target"); return arb; }
  if (name.equals("getTimestampCounterBits")) return bits;
  if (name.equals("isQueryBufferBound")) return queryBufferBound;
  if (name.equals("glGenQueries")) { if (fail) throw new IllegalStateException("injected native failure"); return next++; }
  if (name.equals("glDeleteQueries")) { check(a[0].equals(41), "delete_id"); return null; }
  if (name.equals("glQueryCounter")) { check(a[0].equals(41) && a[1].equals(0x8E28), "timestamp_target"); return null; }
  if (name.equals("glGetQueryObjectui64")) {
   check(!queryBufferBound, "no_read_into_query_buffer");
   check(a[0].equals(41), "read_id");
   int pname = (Integer)a[1];
   if (pname == 0x8867) return ready ? 1L : 0L;
   check(pname == 0x8866 && ready, "available_without_result_read");
   return Long.MIN_VALUE + 42L;
  }
  throw new AssertionError("Unexpected native/service call: " + name);
 }
 static void check(boolean ok, String label) {
  checks++; if (!ok) throw new AssertionError("ASSERT " + label);
 }
 void install() {
  LWJGLServiceProvider.LWJGL = (ILWJGLService) Proxy.newProxyInstance(ILWJGLService.class.getClassLoader(), new Class<?>[]{ILWJGLService.class}, this);
 }
 static void rejected(Runnable call, String label) {
  try { call.run(); throw new AssertionError("ASSERT " + label); }
  catch (IllegalStateException expected) { checks++; }
 }
 public static void main(String[] unused) throws Exception {
  GpuBackendProbe gl = new GpuBackendProbe(); gl.install();
  gl.token = null;
  check(MiraiGlGpuTimerBackend.createIfSupported() == null, "no_context");
  check(gl.calls.equals(Arrays.asList("getCurrentContextToken")), "no_gl_without_context");
  check(MiraiDiagnostics.values.get("gpu.timer_capability").equals("no_context"), "metadata_no_context");
  check(MiraiDiagnostics.values.get("gpu.timer_counter_bits").equals("unknown"), "metadata_unknown_bits");
  check(MiraiDiagnostics.values.get("gpu.timer_supported").equals("false"), "metadata_unsupported");
  gl.token = new Object(); gl.core = false; gl.calls.clear();
  check(MiraiGlGpuTimerBackend.createIfSupported() == null, "no_timer_support");
  check(!gl.calls.contains("getTimestampCounterBits"), "no_bits_query_without_support");
  check(MiraiDiagnostics.values.get("gpu.timer_capability").equals("none"), "metadata_no_capability");
  gl.core = true;
  for (int width : new int[]{0, 30, 32, 63, 65}) {
   gl.bits = width; check(MiraiGlGpuTimerBackend.createIfSupported() == null, "reject_non64");
   check(MiraiDiagnostics.values.get("gpu.timer_counter_bits").equals(Integer.toString(width)), "metadata_actual_bits");
   check(MiraiDiagnostics.values.get("gpu.timer_supported").equals("false"), "metadata_rejected_bits");
  }
  gl.bits = 64; gl.calls.clear();
  final MiraiGlGpuTimerBackend backend = MiraiGlGpuTimerBackend.createIfSupported();
  check(backend != null && backend.counterBits() == 64, "core_supported");
  check(!gl.calls.contains("glGenQueries"), "no_eager_queries");
  check(!gl.calls.contains("isExtensionSupported"), "core_dispatch_short_circuit");
  check(MiraiDiagnostics.values.get("gpu.timer_capability").equals("OpenGL33"), "metadata_core");
  check(MiraiDiagnostics.values.get("gpu.timer_supported").equals("true"), "metadata_supported");
  gl.core = false; gl.arb = true;
  check(MiraiGlGpuTimerBackend.createIfSupported() != null, "arb_supported");
  check(MiraiDiagnostics.values.get("gpu.timer_capability").equals("ARB_timer_query"), "metadata_arb");
  check(MiraiDiagnostics.values.size() == 3, "metadata_bounded_keys");
  check(backend.isCurrentContext(), "owner_context");
  check(backend.create() == 41, "generated_id");
  backend.timestamp(41);
  check(!backend.available(41), "not_ready");
  gl.queryBufferBound = true; gl.ready = true;
  int callsBeforeBuffer = gl.calls.size();
  check(!backend.available(41), "query_buffer_skips_collection");
  List<String> boundCalls = gl.calls.subList(callsBeforeBuffer, gl.calls.size());
  check(Collections.frequency(boundCalls, "isQueryBufferBound") == 1, "query_buffer_single_binding_check");
  for (String call : boundCalls) check(!call.startsWith("gl"), "query_buffer_no_getter_or_rebinding");
  gl.queryBufferBound = false;
  gl.ready = true; check(backend.available(41), "ready");
  check(backend.result(41) == Long.MIN_VALUE + 42L, "unsigned_bits_preserved");
  backend.delete(41);
  final int callsBeforeThread = gl.calls.size();
  final Throwable[] threadFailure = new Throwable[1];
  Thread thread = new Thread(new Runnable() { public void run() {
   try {
    check(!backend.isCurrentContext(), "wrong_thread_context");
    rejected(new Runnable(){public void run(){backend.create();}}, "thread_create");
    rejected(new Runnable(){public void run(){backend.delete(41);}}, "thread_delete");
    rejected(new Runnable(){public void run(){backend.timestamp(41);}}, "thread_timestamp");
    rejected(new Runnable(){public void run(){backend.available(41);}}, "thread_available");
    rejected(new Runnable(){public void run(){backend.result(41);}}, "thread_result");
   } catch (Throwable t) { threadFailure[0] = t; }
  }});
  thread.start(); thread.join();
  if (threadFailure[0] != null) throw new AssertionError(threadFailure[0]);
  check(gl.calls.size() == callsBeforeThread, "wrong_thread_no_service_calls");
  Object original = gl.token; gl.token = new Object(); gl.calls.clear();
  check(!backend.isCurrentContext(), "changed_context");
  rejected(new Runnable(){public void run(){backend.create();}}, "context_create");
  rejected(new Runnable(){public void run(){backend.delete(41);}}, "context_delete");
  rejected(new Runnable(){public void run(){backend.timestamp(41);}}, "context_timestamp");
  rejected(new Runnable(){public void run(){backend.available(41);}}, "context_available");
  rejected(new Runnable(){public void run(){backend.result(41);}}, "context_result");
  for (String name : gl.calls) check(name.equals("getCurrentContextToken"), "changed_context_no_native_calls");
  gl.token = null; check(!backend.isCurrentContext(), "detached_context");
  gl.token = original; check(backend.isCurrentContext(), "restored_context");
  gl.fail = true;
  try { backend.create(); throw new AssertionError("ASSERT native_failure_propagated"); }
  catch (IllegalStateException expected) { check(expected.getMessage().equals("injected native failure"), "native_failure_propagated"); }
  check(ILWJGLService.class.getMethod("getCurrentContextToken").isDefault(), "token_binary_compatible_default");
  check(ILWJGLService.class.getMethod("getTimestampCounterBits").isDefault(), "bits_binary_compatible_default");
  check(ILWJGLService.class.getMethod("isQueryBufferBound").isDefault(), "query_buffer_binary_compatible_default");
  System.out.println("PASS backend " + checks + " checks");
 }
}
'''
}

original = (ROOT / BACKEND).read_text(encoding='utf-8')
mutations = {
 'no_context_guard': ('        this.requireCurrentContext();', '', 'ASSERT thread_create'),
 'no_bitwidth_guard': ('if (bits != 64)', 'if (bits == -1)', 'ASSERT reject_non64'),
 'available_reads_result': ('GL_QUERY_RESULT_AVAILABLE) != 0L', 'GL_QUERY_RESULT) != 0L', 'ASSERT available_without_result_read'),
 'wrong_timestamp_target': ('glQueryCounter(query, GL_TIMESTAMP)', 'glQueryCounter(query, 0x88BF)', 'ASSERT timestamp_target'),
 'no_query_buffer_guard': ('if (this.service.isQueryBufferBound()) { return false; }', '', 'ASSERT no_read_into_query_buffer'),
}
results = []
for name in ['current'] + list(mutations):
    folder = args.out / name
    src, classes = folder / 'src', folder / 'classes'
    src.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    # All production sources remain complete. Only negative controls are mutated.
    for filename in ['ILWJGLService.java', 'AbstractMemoryStack.java', 'EGLExtension.java', 'IDebugMessageHandler.java', 'EDebugExtension.java']:
        target = src / 'com/seibel/distanthorizons/lwjgl' / filename
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / LWJGL / filename, target)
    for path, source in FIXTURES.items():
        target = src / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding='utf-8')
    source = original
    if name != 'current':
        old, replacement, _ = mutations[name]
        if old not in source:
            raise AssertionError('Mutation no longer applicable: ' + name)
        source = source.replace(old, replacement)
    target = src / 'com/seibel/distanthorizons/common/render/openGl/MiraiGlGpuTimerBackend.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source, encoding='utf-8')
    compile_result = subprocess.run([args.javac, '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-d', str(classes)] + [str(p) for p in src.rglob('*.java')], capture_output=True, text=True)
    (folder / 'compile.txt').write_text(compile_result.stdout + compile_result.stderr, encoding='utf-8')
    if compile_result.returncode:
        raise RuntimeError('Compilation failed; never accepted as expected negative: ' + str(folder))
    run = subprocess.run([args.java, '-cp', str(classes), 'GpuBackendProbe'], capture_output=True, text=True)
    (folder / 'run.txt').write_text(run.stdout + run.stderr, encoding='utf-8')
    passed = (run.returncode == 0 and 'PASS backend ' in run.stdout) if name == 'current' else (run.returncode != 0 and mutations[name][2] in run.stderr)
    results.append({'name': name, 'exit_code': run.returncode, 'passed': passed, 'stdout': run.stdout.strip(), 'expected_failure': None if name == 'current' else mutations[name][2]})

runtime = subprocess.run([args.java, '-version'], capture_output=True, text=True)
manifest = {'passed': all(r['passed'] for r in results), 'backend_sha256': hashlib.sha256((ROOT / BACKEND).read_bytes()).hexdigest(), 'java_exe': args.java, 'javac_exe': args.javac, 'java_version': runtime.stdout + runtime.stderr, 'scope': 'Real backend and ILWJGLService; strict proxy replacing native driver and factory/facade boundary; no GPU/context created', 'results': results}
(args.out / 'results.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
print(json.dumps(manifest, indent=2))
raise SystemExit(0 if manifest['passed'] else 1)
