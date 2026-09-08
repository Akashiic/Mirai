#!/usr/bin/env python3
"""Compile the complete production GLBuffer against a strict in-memory GL fixture.

No GPU or Minecraft is used. The fixture checks immutable-storage rules, payload,
binding restoration, lifetime accounting and failures before/after allocation.
Optional --baseline-root runs the same scenarios against the untouched 0.1.7 source.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path('common/src/main/java/com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLBuffer.java')
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline-root', type=Path)
parser.add_argument('--out', type=Path, default=ROOT.parent / 'akashic-audit/evidence/storage-lifecycle')
parser.add_argument('--javac', default=shutil.which('javac'))
parser.add_argument('--java', default=shutil.which('java'))
args = parser.parse_args()
if not args.javac or not args.java:
    parser.error('Supply --javac and --java or put a JDK on PATH.')

fixtures = {
'com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod': '''
public enum EDhApiGpuUploadMethod {
 AUTO(false), BUFFER_STORAGE(true), DATA(false), SUB_DATA(false);
 public final boolean useBufferStorage; public final boolean useEarlyMapping=false;
 EDhApiGpuUploadMethod(boolean storage){useBufferStorage=storage;}
}''',
'com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy': '''
public class GLProxy {public static boolean runningOnRenderThread(){return true;}}''',
'com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper': '''
public class MinecraftGLWrapper {
 public static final MinecraftGLWrapper INSTANCE=new MinecraftGLWrapper();
 public int glGenBuffers(){return fixture.Driver.generate();}
 public void glDeleteBuffers(int id){fixture.Driver.delete(id);}
}''',
'com.seibel.distanthorizons.core.config.Config': '''
public class Config {
 public static class Value {public boolean get(){return false;}}
 public static class Common {public static class Logging {
  public static Object logRendererGLEventToFile,logRendererGLEventToChat;
 }}
 public static class Client {public static class Advanced {public static class Debugging {
  public static Value logBufferGarbageCollection=new Value();
 }}}
}''',
'com.seibel.distanthorizons.core.jar.EPlatform': '''
public enum EPlatform {WINDOWS,MACOS; public static EPlatform get(){return WINDOWS;}}''',
'com.seibel.distanthorizons.core.logging.DhLogger': '''
public class DhLogger {
 public void warn(String s){throw new AssertionError("Unexpected warning: "+s);}
 public void info(String s){}
 public void error(String s,Throwable t){throw new AssertionError(s,t);}
}''',
'com.seibel.distanthorizons.core.logging.DhLoggerBuilder': '''
public class DhLoggerBuilder {
 public DhLoggerBuilder fileLevelConfig(Object o){return this;}
 public DhLoggerBuilder chatLevelConfig(Object o){return this;}
 public DhLogger build(){return new DhLogger();}
}''',
'com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics': '''
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
public class MiraiDiagnostics {
 public static long startTiming(){return 0;}
 public static void finishBufferUpload(EDhApiGpuUploadMethod m,int b,long s,boolean ok){}
}''',
'com.seibel.distanthorizons.core.logging.f3.F3Screen': '''
public class F3Screen {public static final java.text.NumberFormat NUMBER_FORMAT=java.text.NumberFormat.getInstance();}''',
'com.seibel.distanthorizons.core.render.RenderThreadTaskHandler': '''
public class RenderThreadTaskHandler {
 public static final RenderThreadTaskHandler INSTANCE=new RenderThreadTaskHandler();
 public static final java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<Runnable>();
 public void queueRunningOnRenderThread(String name,Runnable task){tasks.add(task);}
 public boolean isCurrentThread(){return true;}
 public QueuedRunnable getCurrentlyRunningTask(){return null;}
 public static class QueuedRunnable {public StackTraceElement[] stackTrace;}
 public static void drain(){while(!tasks.isEmpty())tasks.remove().run();}
}''',
'com.seibel.distanthorizons.core.util.LodUtil': '''
public class LodUtil {
 public static void assertTrue(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void assertNotReach(String s){throw new AssertionError(s);}
}''',
'com.seibel.distanthorizons.core.util.ThreadUtil': '''
public class ThreadUtil {
 public static java.util.concurrent.ThreadPoolExecutor makeSingleDaemonThreadPool(String name){
  return new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
   new java.util.concurrent.LinkedBlockingQueue<Runnable>(), r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;});
 }
}''',
'com.seibel.distanthorizons.core.util.objects.Pair': 'public class Pair<A,B> {}',
'com.seibel.distanthorizons.core.util.objects.pooling.PhantomLoggingHelper': '''
public class PhantomLoggingHelper {
 public static void putAndIncrementTrackingString(String s,java.util.List<?> p){}
 public static void LogAllocationStackTracePairCounts(Object o,java.util.List<?> p){}
}''',
'com.seibel.distanthorizons.coreapi.ModInfo': 'public class ModInfo {public static final boolean IS_DEV_BUILD=true;}',
'com.seibel.distanthorizons.coreapi.util.StringUtil': '''
public class StringUtil {public static String join(String s,Object[] a){return java.util.Arrays.toString(a);}}''',
'org.lwjgl.opengl.GL15': '''
public class GL15 {
 public static final int GL_ARRAY_BUFFER=34962, GL_ELEMENT_ARRAY_BUFFER=34963,
  GL_ARRAY_BUFFER_BINDING=34964,GL_ELEMENT_ARRAY_BUFFER_BINDING=34965;
 public static boolean glIsBuffer(int id){return fixture.Driver.buffers.containsKey(id);}
}''',
'org.lwjgl.opengl.GL30': '''
public class GL30 {public static final int GL_VERTEX_ARRAY_BINDING=34229;
 public static boolean glIsVertexArray(int id){return id==fixture.Driver.EXTERNAL_VAO;}}''',
'org.lwjgl.opengl.GL31': 'public class GL31 {public static final int GL_COPY_READ_BUFFER=36662;}',
'org.lwjgl.opengl.GL33': 'public class GL33 {}',
'com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider': '''
public class LWJGLServiceProvider {public static final fixture.Driver LWJGL=new fixture.Driver();}''',
}

fixtures['fixture.Driver'] = r'''
import java.nio.ByteBuffer; import java.util.*; import org.lwjgl.opengl.*;
public class Driver {
 public static final int EXTERNAL_VAO=8, EXTERNAL_VBO=5001, EXTERNAL_EBO=5002;
 static final class Buffer {boolean immutable; byte[] data;}
 public static final Map<Integer,Buffer> buffers=new HashMap<Integer,Buffer>();
 static final Map<Integer,Integer> bindings=new HashMap<Integer,Integer>();
 public static int vao=EXTERNAL_VAO,generated,deleted,storageCalls,dataCalls,nextId=100;
 public static int failure; // 1: before allocation; 2: after allocation
 static {buffers.put(EXTERNAL_VBO,new Buffer());buffers.put(EXTERNAL_EBO,new Buffer());
  bindings.put(GL15.GL_ARRAY_BUFFER,EXTERNAL_VBO);bindings.put(GL15.GL_ELEMENT_ARRAY_BUFFER,EXTERNAL_EBO);}
 public static int generate(){int id=++nextId;buffers.put(id,new Buffer());generated++;return id;}
 public static void delete(int id){if(buffers.remove(id)==null)throw new AssertionError("double delete "+id);deleted++;
  for(Map.Entry<Integer,Integer> e:bindings.entrySet())if(e.getValue()==id)e.setValue(0);}
 public int glGetInteger(int key){if(key==GL30.GL_VERTEX_ARRAY_BINDING)return vao;
  if(key==GL15.GL_ARRAY_BUFFER_BINDING)return bound(GL15.GL_ARRAY_BUFFER);
  if(key==GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)return bound(GL15.GL_ELEMENT_ARRAY_BUFFER);
  throw new AssertionError("unknown query "+key);}
 public void glBindVertexArray(int id){vao=id;}
 public void glBindBuffer(int target,int id){if(id!=0&&!buffers.containsKey(id))throw new AssertionError("foreign buffer "+id);bindings.put(target,id);}
 public static int bound(int target){Integer id=bindings.get(target);return id==null?0:id;}
 private static Buffer require(int target){Buffer b=buffers.get(bound(target));if(b==null)throw new AssertionError("no buffer bound");return b;}
 public void glBufferStorage(int target,ByteBuffer input,int flags){
  storageCalls++; Buffer b=require(target);
  if(b.immutable)throw new AssertionError("GL_INVALID_OPERATION: immutable storage already exists");
  int fail=failure;failure=0;if(fail==1)throw new IllegalStateException("before allocation");
  b.data=payload(input);b.immutable=true;
  if(fail==2)throw new IllegalStateException("after allocation");
 }
 public void glBufferData(int target,long size,int hint){Buffer b=require(target);if(b.immutable)throw new AssertionError("immutable DATA");b.data=new byte[(int)size];dataCalls++;}
 public void glBufferData(int target,ByteBuffer input,int hint){Buffer b=require(target);if(b.immutable)throw new AssertionError("immutable DATA");b.data=payload(input);dataCalls++;}
 public void glBufferSubData(int target,long offset,ByteBuffer input){Buffer b=require(target);if(b.immutable||b.data==null||offset+input.remaining()>b.data.length)throw new AssertionError("invalid SUB_DATA store");
  byte[] p=payload(input);System.arraycopy(p,0,b.data,(int)offset,p.length);}
 public void glFlush(){}
 public static byte[] payload(ByteBuffer input){ByteBuffer view=input.duplicate();byte[] data=new byte[view.remaining()];view.get(data);return data;}
 public static byte[] payload(int id){return buffers.get(id).data;}
}
'''

fixtures['fixture.StorageProbe'] = r'''
import java.nio.*;import java.util.*;import java.lang.reflect.*;
import org.lwjgl.opengl.GL15;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLBuffer;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
public class StorageProbe {
 static int checks;
 static class VertexBuffer extends GLBuffer {
  VertexBuffer(boolean storage){super(storage);}
  @Override public int getBufferBindingTarget(){return GL15.GL_ARRAY_BUFFER;}
 }
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
 static ByteBuffer input(){ByteBuffer b=ByteBuffer.allocateDirect(16);for(int i=0;i<16;i++)b.put((byte)(i+20));b.limit(10);b.position(2);return b;}
 static void restored(){check(Driver.vao==Driver.EXTERNAL_VAO,"VAO restored");
  check(Driver.bound(GL15.GL_ARRAY_BUFFER)==Driver.EXTERNAL_VBO,"external VBO restored");
  check(Driver.bound(GL15.GL_ELEMENT_ARRAY_BUFFER)==Driver.EXTERNAL_EBO,"external EBO restored");}
 static void upload(VertexBuffer b,EDhApiGpuUploadMethod method){ByteBuffer input=input();byte[] expected=Driver.payload(input);
  try{b.uploadBuffer(input,method,128,0);}finally{restored();check(input.position()==2&&input.limit()==10,"input range unchanged");}
  check(Arrays.equals(Arrays.copyOf(Driver.payload(b.getId()),expected.length),expected),"payload identical");
  check(b.getSize()>=expected.length,"valid data size");
 }
 static void storage(VertexBuffer b){upload(b,EDhApiGpuUploadMethod.BUFFER_STORAGE);}
 static void accounted()throws Exception {
  check(GLBuffer.bufferCount.get()==Driver.generated-Driver.deleted,"driver and live counts agree");
  for(String name:new String[]{"BUFFER_ID_TO_PHANTOM","PHANTOM_TO_BUFFER_ID"}){
   Field f=GLBuffer.class.getDeclaredField(name);f.setAccessible(true);
   check(((Map<?,?>)f.get(null)).size()==GLBuffer.bufferCount.get(),"phantom accounting "+name);
  }
 }
 static void close(VertexBuffer b)throws Exception{b.close();b.close();RenderThreadTaskHandler.drain();accounted();check(GLBuffer.bufferCount.get()==0,"all owned buffers released");}
 public static void main(String[] args)throws Exception{
  String scenario=args[0];VertexBuffer b=new VertexBuffer(!scenario.equals("type_transition"));
  if(scenario.equals("first")){
   int id=b.getId();storage(b);check(b.getId()==id,"first upload reuses fresh ID");
   check(Driver.generated==1&&Driver.deleted==0,"first upload has no redundant gen/delete");
  }else if(scenario.equals("repeated")){
   storage(b);int id=b.getId(),g=Driver.generated,d=Driver.deleted;
   storage(b);check(b.getId()!=id,"immutable reupload replaces ID");
   check(Driver.generated==g+1&&Driver.deleted==d+1,"exactly one replacement");
  }else if(scenario.equals("type_transition")){
   upload(b,EDhApiGpuUploadMethod.DATA);int g=Driver.generated,d=Driver.deleted;
   storage(b);check(Driver.generated==g+1&&Driver.deleted==d+1,"DATA to STORAGE replaces once");
  }else if(scenario.equals("storage_to_sub_data")){
   storage(b);upload(b,EDhApiGpuUploadMethod.SUB_DATA);
   check(Driver.dataCalls==1,"new mutable ID receives backing storage");
  }else if(scenario.equals("close_reuse")){
   storage(b);int oldId=b.getId();b.close();int g=Driver.generated;
   check(b.getId()==0&&b.getSize()==0,"close invalidates ID and size");
   storage(b);check(Driver.generated==g+1,"reuse creates one fresh ID");
   int id=b.getId();RenderThreadTaskHandler.drain();
   check(id!=oldId&&Driver.buffers.containsKey(id)&&!Driver.buffers.containsKey(oldId),"deferred close frees only old ID");
  }else if(scenario.equals("failure_before")||scenario.equals("failure_after")){
   Driver.failure=scenario.equals("failure_before")?1:2;boolean failed=false;
   try{storage(b);}catch(IllegalStateException expected){failed=true;}
   check(failed,"injected failure propagated");int id=b.getId(),g=Driver.generated;
   check(b.getSize()==0,"failed upload does not publish size");
   storage(b);check(b.getId()!=id&&Driver.generated==g+1,"retry conservatively replaces possibly immutable ID");
  }else if(scenario.equals("empty")){
   int id=b.getId();ByteBuffer input=input();input.limit(input.position());b.uploadBuffer(input,EDhApiGpuUploadMethod.BUFFER_STORAGE,128,0);
   check(Driver.storageCalls==0&&Driver.generated==1&&b.getId()==id,"empty upload does not allocate");restored();
  }else if(scenario.equals("bulk")){
   List<VertexBuffer> list=new ArrayList<VertexBuffer>();list.add(b);
   for(int i=1;i<128;i++)list.add(new VertexBuffer(true));
   for(VertexBuffer item:list)storage(item);
   check(Driver.generated==128&&Driver.deleted==0&&Driver.storageCalls==128,"128 first uploads require 128 IDs and zero replacements");
   for(VertexBuffer item:list)item.close();RenderThreadTaskHandler.drain();
   accounted();check(GLBuffer.bufferCount.get()==0,"bulk fully released");
   System.out.println("PASS "+scenario+" checks="+checks);return;
  }else throw new AssertionError("unknown scenario "+scenario);
  accounted();close(b);System.out.println("PASS "+scenario+" checks="+checks);
 }
}
'''

scenarios = ['first', 'repeated', 'type_transition', 'storage_to_sub_data', 'close_reuse',
             'failure_before', 'failure_after', 'empty', 'bulk']
baseline_failures = {
    'first': 'java.lang.AssertionError: first upload reuses fresh ID',
    'type_transition': 'java.lang.AssertionError: DATA to STORAGE replaces once',
    'storage_to_sub_data': 'java.lang.AssertionError: invalid SUB_DATA store',
    'close_reuse': 'java.lang.AssertionError: reuse creates one fresh ID',
    'bulk': 'java.lang.AssertionError: 128 first uploads require 128 IDs and zero replacements',
}
toolchain = {}
for name, executable in [('javac', args.javac), ('java', args.java)]:
    version = subprocess.run([executable, '-version'], capture_output=True, text=True, timeout=30)
    if version.returncode:
        raise SystemExit(version.stdout + version.stderr)
    toolchain[name] = {'executable': str(Path(executable).resolve()),
                       'version': (version.stdout + version.stderr).strip()}
manifest = {'scope': 'Complete unchanged GLBuffer source compiled against strict GL/dependency fixtures; no GPU or performance measurement.',
            'toolchain': toolchain, 'runs': {}}
for label, root in [('candidate', ROOT)] + ([('baseline', args.baseline_root)] if args.baseline_root else []):
    out = args.out / label
    src = out / 'src'
    classes = out / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for name, body in fixtures.items():
        package, simple = name.rsplit('.', 1)
        file = src / Path(name.replace('.', '/')).with_suffix('.java')
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('package ' + package + ';\n' + body, encoding='utf-8')
    original = (root / SOURCE).read_bytes()
    production = src / 'com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLBuffer.java'
    production.parent.mkdir(parents=True, exist_ok=True)
    production.write_bytes(original)
    command = [args.javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(classes)]
    command += [str(p) for p in src.rglob('*.java')]
    compile_result = subprocess.run(command, capture_output=True, text=True)
    (out / 'compile.log').write_text(compile_result.stdout + compile_result.stderr, encoding='utf-8')
    if compile_result.returncode:
        raise SystemExit(compile_result.stdout + compile_result.stderr)
    results = {}
    for scenario in scenarios:
        result = subprocess.run([args.java, '-Xverify:all', '-cp', str(classes), 'fixture.StorageProbe', scenario], capture_output=True, text=True, timeout=30)
        (out / (scenario + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        expected_failure = label == 'baseline' and scenario in baseline_failures
        expected_message = baseline_failures[scenario] if expected_failure else 'PASS ' + scenario + ' checks='
        if expected_failure:
            matched = result.returncode != 0 and expected_message in result.stderr
        else:
            matched = result.returncode == 0 and expected_message in result.stdout
        results[scenario] = {'exit_code': result.returncode, 'expected_failure': expected_failure,
                             'expected_message': expected_message, 'expectation_met': matched}
        print(label, scenario, 'EXPECTED_FAIL' if expected_failure and matched else 'PASS' if matched else 'UNEXPECTED', result.stdout.strip())
        if not matched:
            raise SystemExit(result.stdout + result.stderr + '\nUnexpected outcome: ' + label + ' ' + scenario)
    manifest['runs'][label] = {'source_sha256': hashlib.sha256(original).hexdigest(), 'source': str(root / SOURCE), 'scenarios': results}
args.out.mkdir(parents=True, exist_ok=True)
(args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print('All expected outcomes verified; manifest:', args.out / 'manifest.json')
