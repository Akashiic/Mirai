#!/usr/bin/env python3
"""Test exact six adapter method bodies with explicit allocator/GL fixtures (no GPU)."""
from pathlib import Path
import subprocess,json,hashlib
ROOT=Path(__file__).resolve().parents[2]; WORK=ROOT.parent
OUT=WORK/'stabilization-0.1.3-evidence/rendering/buffers';OUT.mkdir(parents=True,exist_ok=True)
p=ROOT/'common/src/lwjgl2/java/com/seibel/distanthorizons/lwjgl/lwjgl2/LWJGL2Service.java';src=p.read_text()
sigs=['public void glBufferData(int target, int[] data, int usage)','public void glBufferData(int target, float[] data, int usage)','public void glGenTextures(int[] textures)','public void glDeleteTextures(int[] textures)','public void glGetIntegerv(int pname, int[] params)','public void glGetFloatv(int pname, float[] params)']
methods=[];manifest={}
for sig in sigs:
 a=src.index(sig);b=src.index('{',a);depth=1;i=b+1
 while depth:
  if src[i]=='{':depth+=1
  if src[i]=='}':depth-=1
  i+=1
 method=src[a:i];methods.append(method);manifest[sig]=hashlib.sha256(method.encode()).hexdigest()
code='import java.nio.*; import java.util.*;\npublic final class NativeBufferProbe {\n'+('\n'.join(methods))+r'''
 static int allocated,freed,checks; static boolean glFailure,shortBuffer; static Set<Buffer> live=Collections.newSetFromMap(new IdentityHashMap<Buffer,Boolean>());
 static class MemoryUtilities {
  static IntBuffer memAllocInt(int count){IntBuffer b=ByteBuffer.allocateDirect((shortBuffer?0:count)*4).asIntBuffer();allocated++;live.add(b);return b;}
  static FloatBuffer memAllocFloat(int count){FloatBuffer b=ByteBuffer.allocateDirect((shortBuffer?0:count)*4).asFloatBuffer();allocated++;live.add(b);return b;}
  static void memFree(Buffer b){if(!live.remove(b))throw new AssertionError("double free or foreign buffer");freed++;}
 }
 static void check(boolean good,String msg){if(!good)throw new AssertionError(msg);checks++;}
 static void nativeCall(){if(glFailure)throw new IllegalStateException("injected GL exception");}
 static class GL15 {static void glBufferData(int t,IntBuffer b,int u){nativeCall();}static void glBufferData(int t,FloatBuffer b,int u){nativeCall();}}
 static class GL11 {
  static void glGenTextures(IntBuffer b){nativeCall();for(int i=0;i<b.capacity();i++)b.put(i,100+i);}
  static void glDeleteTextures(IntBuffer b){nativeCall();}
  static void glGetInteger(int p,IntBuffer b){nativeCall();for(int i=0;i<b.capacity();i++)b.put(i,100+i);}
  static void glGetFloat(int p,FloatBuffer b){nativeCall();for(int i=0;i<b.capacity();i++)b.put(i,.5f+i);}
 }
 public static void main(String[] args){NativeBufferProbe s=new NativeBufferProbe();
  for(int phase=0;phase<3;phase++){glFailure=phase==1;shortBuffer=phase==2;
   for(int method=0;method<6;method++){int[] ints={1,2,3};float[] floats={1,2,3};int a=allocated,f=freed;boolean failed=false;
    try{switch(method){case 0:s.glBufferData(0,ints,0);break;case 1:s.glBufferData(0,floats,0);break;case 2:s.glGenTextures(ints);break;case 3:s.glDeleteTextures(ints);break;case 4:s.glGetIntegerv(0,ints);break;case 5:s.glGetFloatv(0,floats);break;}}
    catch(RuntimeException expected){failed=true;}
    check(failed==(phase!=0),"fault outcome method="+method+" phase="+phase);
    check(allocated==a+1&&freed==f+1&&live.isEmpty(),"allocation released exactly once method="+method+" phase="+phase);
    if(phase==0&&(method==2||method==4))check(Arrays.equals(ints,new int[]{100,101,102}),"integer output copied");
    if(phase==0&&method==5)check(Arrays.equals(floats,new float[]{.5f,1.5f,2.5f}),"float output copied");
   }
  }
  System.out.println("PASS "+checks+" buffer checks; six exact production method bodies, native-call and NIO failure fixtures; no GPU/native allocator");
 }
}
'''
(OUT/'NativeBufferProbe.java').write_text(code)
for version in ['jdk8','jdk21']:
 j=WORK/'toolchains'/version/'bin'
 if version=='jdk8':
  r=subprocess.run([str(j/'javac'),'-source','8','-target','8','-d',str(OUT),str(OUT/'NativeBufferProbe.java')],text=True,capture_output=True);(OUT/'compile.log').write_text(r.stdout+r.stderr)
  if r.returncode:print(r.stderr);raise SystemExit(r.returncode)
 r=subprocess.run([str(j/'java'),'-Xverify:all','-cp',str(OUT),'NativeBufferProbe'],text=True,capture_output=True);(OUT/(version+'.log')).write_text(r.stdout+r.stderr);print(version,r.stdout,r.stderr)
 if r.returncode:raise SystemExit(r.returncode)
(OUT/'METHOD-SHA256.json').write_text(json.dumps(manifest,indent=2)+'\n')
