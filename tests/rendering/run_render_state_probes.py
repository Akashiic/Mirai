#!/usr/bin/env python3
"""Compile the actual GLState/cleanup sources against a declared, fault-injecting GL fixture.
No Minecraft, native allocator, driver, or GPU is exercised by this test.
"""
from pathlib import Path
import re, subprocess, json, hashlib, shutil
ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT.parent
OUT = WORK / 'stabilization-0.1.3-evidence/rendering/state'
SRC = OUT/'src'; CLASSES = OUT/'classes'
SRC.mkdir(parents=True, exist_ok=True); CLASSES.mkdir(exist_ok=True)
J8=WORK/'toolchains/jdk8/bin'; J21=WORK/'toolchains/jdk21/bin'
JAR=WORK/'deliverables-0.1.2/Akashic-Horizons-of-The-Mirai-forge17-0.1.2-dev-1.7.10.jar'
LWJGL=next((WORK/'gradle-cache').rglob('lwjgl-2.9.1.jar'))
files=['common/src/main/java/com/seibel/distanthorizons/common/render/openGl/glObject/GLState.java','common/src/main/java/com/seibel/distanthorizons/common/render/openGl/util/GlResourceCleanup.java','coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhMetaRenderer.java']
manifest={}
def write(pkg,name,body):
 p=SRC/Path(pkg.replace('.','/'))/(name+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package '+pkg+';\n'+body);return p
for name in files:
 data=(ROOT/name).read_text(); pkg=re.search(r'package ([\w.]+);',data)[1]
 p=SRC/Path(pkg.replace('.','/'))/Path(name).name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
 manifest[name]=hashlib.sha256(data.encode()).hexdigest()
state=(ROOT/files[0]).read_text()
# Constants come from the pinned LWJGL 2.9.1 dependency; only native methods are fixtures.
for gl in ['GL11','GL13','GL14','GL15','GL20','GL30']:
 result=subprocess.check_output([str(J8/'javap'),'-constants','-classpath',str(LWJGL),'org.lwjgl.opengl.'+gl],text=True)
 consts='\n'.join(re.findall(r'  public static final int \w+ = -?\d+;',result))
 methods={
 'GL11':'public static double glGetDouble(int p) {return fixture.Driver.clearDepth;}',
 'GL15':'public static boolean glIsBuffer(int x) {return x>0;}',
 'GL20':'public static boolean glIsProgram(int x) {return x>0;}',
 'GL30':'''public static boolean glIsFramebuffer(int x) {return x>0;} public static boolean glIsVertexArray(int x){return x>0;} public static void glFramebufferRenderbuffer(int t,int a,int rt,int id){fixture.Driver.attach(a,rt,id,0,0);}'''
 }.get(gl,'')
 write('org.lwjgl.opengl',gl,'public final class '+gl+' {\n'+consts+'\n'+methods+'\n}')
write('com.seibel.distanthorizons.lwjgl','LWJGLServiceProvider','public final class LWJGLServiceProvider { public static final ILWJGLService LWJGL=fixture.Driver.create(); }')
write('com.seibel.distanthorizons.common.render.openGl.glObject.enums','GLEnums','public final class GLEnums {public static String getString(int x){return String.valueOf(x);}}')
wrap='import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL; import org.lwjgl.opengl.*; public final class MinecraftGLWrapper {public static final MinecraftGLWrapper INSTANCE=new MinecraftGLWrapper();\n'
for method,cap in [('Blend','GL_BLEND'),('ScissorTest','GL_SCISSOR_TEST'),('DepthTest','GL_DEPTH_TEST'),('FaceCulling','GL_CULL_FACE')]:
 for prefix,glfun in [('enable','glEnable'),('disable','glDisable')]:wrap+=f'public void {prefix}{method}(){{LWJGL.{glfun}(GL11.{cap});}}\n'
wrap+='public void enableDepthMask(){LWJGL.glDepthMask(true);} public void disableDepthMask(){LWJGL.glDepthMask(false);}\n'
for name,args,call in [('glActiveTexture','int x','x'),('glBindFramebuffer','int t,int f','t,f'),('glBlendFunc','int s,int d','s,d'),('glBlendFuncSeparate','int a,int b,int c,int d','a,b,c,d'),('glDepthFunc','int x','x'),('glViewport','int a,int b,int c,int d','a,b,c,d')]:wrap+=f'public void {name}({args}){{LWJGL.{name}({call});}}\n'
wrap+='public void glBindTexture(int x){LWJGL.glBindTexture(GL11.GL_TEXTURE_2D,x);} }'
write('com.seibel.distanthorizons.common.wrappers.minecraft','MinecraftGLWrapper',wrap)
write('fixture','Driver',r'''
import java.lang.reflect.*; import java.util.*; import org.lwjgl.opengl.*; import com.seibel.distanthorizons.lwjgl.ILWJGLService;
public final class Driver implements InvocationHandler {
 public static final Map<Integer,Integer> values=new HashMap<>(), textures=new HashMap<>();
 public static final Map<String,int[]> attachments=new HashMap<>(); public static final Set<Integer> enabled=new HashSet<>();
 public static float[] clear={.12f,.24f,.36f,.48f}; public static double clearDepth=.625;
 public static int[] view={3,7,800,600}; public static String fault; public static int faultUnit=-1; public static int calls;
 public static ILWJGLService create(){return (ILWJGLService) Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{ILWJGLService.class},new Driver());}
 public static void init(){values.clear();textures.clear();attachments.clear();enabled.clear();fault=null;faultUnit=-1;calls=0; clear=new float[]{.12f,.24f,.36f,.48f};clearDepth=.625;view=new int[]{3,7,800,600};
  int[] keys={GL20.GL_CURRENT_PROGRAM,GL30.GL_VERTEX_ARRAY_BINDING,GL15.GL_ARRAY_BUFFER_BINDING,GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING,GL30.GL_DRAW_FRAMEBUFFER_BINDING,GL30.GL_READ_FRAMEBUFFER_BINDING,GL13.GL_ACTIVE_TEXTURE,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA,GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_DST_ALPHA,GL11.GL_DEPTH_WRITEMASK,GL11.GL_DEPTH_FUNC,GL11.GL_STENCIL_FUNC,GL11.GL_STENCIL_REF,GL11.GL_STENCIL_VALUE_MASK,GL11.GL_CULL_FACE_MODE,GL11.GL_POLYGON_MODE};
  int[] vals={11,12,13,14,31,32,GL13.GL_TEXTURE0+5,GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD,GL11.GL_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,1,GL11.GL_LEQUAL,GL11.GL_ALWAYS,2,255,GL11.GL_BACK,GL11.GL_FILL};
  for(int i=0;i<keys.length;i++)values.put(keys[i],vals[i]); for(int i=0;i<6;i++)textures.put(GL13.GL_TEXTURE0+i,100+i);
  enabled.add(GL11.GL_DEPTH_TEST);enabled.add(GL11.GL_SCISSOR_TEST);enabled.add(GL11.GL_CULL_FACE);enabled.add(GL11.GL_STENCIL_TEST);
  attach(GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE,201,2,0);attach(GL30.GL_COLOR_ATTACHMENT1,GL11.GL_NONE,0,0,0);attach(GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,202,0,0);
 }
 public static int get(int k){Integer v=values.get(k);return v==null?0:v;}
 public static void attach(int a,int type,int id,int level,int cube){attachments.put(get(GL30.GL_DRAW_FRAMEBUFFER_BINDING)+":"+a,new int[]{type,id,level,cube});}
 public static String snapshot(){TreeMap<String,String> a=new TreeMap<>();for(Map.Entry<String,int[]> e:attachments.entrySet())if(e.getKey().startsWith("31:"))a.put(e.getKey(),Arrays.toString(e.getValue()));return new TreeMap<>(values)+"|"+new TreeMap<>(textures)+"|"+new TreeSet<>(enabled)+"|"+Arrays.toString(clear)+"|"+clearDepth+"|"+Arrays.toString(view)+"|"+a;}
 public Object invoke(Object p,Method m,Object[] x){String n=m.getName();calls++;
  if(n.equals(fault)&&(faultUnit==-1||get(GL13.GL_ACTIVE_TEXTURE)==faultUnit)){fault=null;throw new IllegalStateException("injected:"+n);}
  switch(n){
   case "glGetInteger": return (int)x[0]==GL11.GL_TEXTURE_BINDING_2D?textures.get(get(GL13.GL_ACTIVE_TEXTURE)):get((int)x[0]);
   case "glGetIntegerv": System.arraycopy(view,0,(int[])x[1],0,4);return null;
   case "glGetFloatv": System.arraycopy(clear,0,(float[])x[1],0,4);return null;
   case "glIsEnabled":return enabled.contains((int)x[0]);case "glIsTexture":return (int)x[0]>0;
   case "glActiveTexture":values.put(GL13.GL_ACTIVE_TEXTURE,(int)x[0]);return null;
   case "glBindTexture":textures.put(get(GL13.GL_ACTIVE_TEXTURE),(int)x[1]);return null;
   case "glBindFramebuffer":{int t=(int)x[0],id=(int)x[1];if(t==GL30.GL_DRAW_FRAMEBUFFER||t==GL30.GL_FRAMEBUFFER)values.put(GL30.GL_DRAW_FRAMEBUFFER_BINDING,id);if(t==GL30.GL_READ_FRAMEBUFFER||t==GL30.GL_FRAMEBUFFER)values.put(GL30.GL_READ_FRAMEBUFFER_BINDING,id);return null;}
   case "glGetFramebufferAttachmentParameteri":{int[] a=attachments.get(get(GL30.GL_DRAW_FRAMEBUFFER_BINDING)+":"+x[1]);if(a==null)a=new int[4];int q=(int)x[2];return q==GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE?a[0]:q==GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME?a[1]:q==GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL?a[2]:a[3];}
   case "glFramebufferTexture2D":attach((int)x[1],(int)x[3]==0?GL11.GL_NONE:GL11.GL_TEXTURE,(int)x[3],(int)x[4],(int)x[2]==GL11.GL_TEXTURE_2D?0:(int)x[2]);return null;
   case "glEnable":enabled.add((int)x[0]);return null;case "glDisable":enabled.remove((int)x[0]);return null;
   case "glBindVertexArray":values.put(GL30.GL_VERTEX_ARRAY_BINDING,(int)x[0]);return null;
   case "glBindBuffer":values.put((int)x[0]==GL15.GL_ARRAY_BUFFER?GL15.GL_ARRAY_BUFFER_BINDING:GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING,(int)x[1]);return null;
   case "glUseProgram":values.put(GL20.GL_CURRENT_PROGRAM,(int)x[0]);return null;
   case "glDepthMask":values.put(GL11.GL_DEPTH_WRITEMASK,(boolean)x[0]?1:0);return null;
   case "glDepthFunc":values.put(GL11.GL_DEPTH_FUNC,(int)x[0]);return null;
   case "glBlendFunc":values.put(GL14.GL_BLEND_SRC_RGB,(int)x[0]);values.put(GL14.GL_BLEND_DST_RGB,(int)x[1]);return null;
   case "glBlendEquationSeparate":values.put(GL20.GL_BLEND_EQUATION_RGB,(int)x[0]);values.put(GL20.GL_BLEND_EQUATION_ALPHA,(int)x[1]);return null;
   case "glBlendFuncSeparate":values.put(GL14.GL_BLEND_SRC_RGB,(int)x[0]);values.put(GL14.GL_BLEND_DST_RGB,(int)x[1]);values.put(GL14.GL_BLEND_SRC_ALPHA,(int)x[2]);values.put(GL14.GL_BLEND_DST_ALPHA,(int)x[3]);return null;
   case "glStencilFunc":values.put(GL11.GL_STENCIL_FUNC,(int)x[0]);values.put(GL11.GL_STENCIL_REF,(int)x[1]);values.put(GL11.GL_STENCIL_VALUE_MASK,(int)x[2]);return null;
   case "glViewport":view=new int[]{(int)x[0],(int)x[1],(int)x[2],(int)x[3]};return null;
   case "glCullFace":values.put(GL11.GL_CULL_FACE_MODE,(int)x[0]);return null;
   case "glPolygonMode":values.put(GL11.GL_POLYGON_MODE,(int)x[1]);return null;
   case "glClearColor":clear=new float[]{(float)x[0],(float)x[1],(float)x[2],(float)x[3]};return null;
   case "glClearDepth":clearDepth=(double)x[0];return null;
   default:throw new AssertionError("Unexpected GL call "+n);
  }
 }
}
''')
write('fixture','RenderStateProbe',r'''
import com.seibel.distanthorizons.common.render.openGl.glObject.GLState; import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL; import org.lwjgl.opengl.*; import java.util.*;
public final class RenderStateProbe {
 static int checks; static void check(boolean x,String s){if(!x)throw new AssertionError(s);checks++;}
 static void mutate(){for(Integer k:new ArrayList<Integer>(Driver.values.keySet()))if(k!=GL13.GL_ACTIVE_TEXTURE&&k!=GL30.GL_DRAW_FRAMEBUFFER_BINDING&&k!=GL30.GL_READ_FRAMEBUFFER_BINDING)Driver.values.put(k,999);for(Integer k:Driver.textures.keySet())if(k!=GL13.GL_TEXTURE0+4)Driver.textures.put(k,777);Driver.enabled.clear();Driver.attach(GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE,888,0,0);Driver.attach(GL30.GL_COLOR_ATTACHMENT1,GL11.GL_TEXTURE,889,0,0);LWJGL.glBindFramebuffer(GL30.GL_FRAMEBUFFER,999);LWJGL.glActiveTexture(GL13.GL_TEXTURE2);LWJGL.glClearColor(1,1,1,0);LWJGL.glClearDepth(1);LWJGL.glViewport(0,0,1,1);}
 public static void main(String[] args){
  Driver.init();String before=Driver.snapshot();try(GLState s=new GLState()){mutate();}check(before.equals(Driver.snapshot()),"full state including split FBO and renderbuffer restored\nbefore "+before+"\nafter "+Driver.snapshot());
  Driver.init();before=Driver.snapshot();RuntimeException primary=new RuntimeException("render failure");try(GLState s=new GLState()){mutate();throw primary;}catch(RuntimeException e){check(e==primary,"render error preserved");}check(before.equals(Driver.snapshot()),"render-failure state restored");
  Driver.init();try(GLState s=new GLState()){mutate();Driver.fault="glUseProgram";throw primary;}catch(RuntimeException e){check(e==primary,"primary preserved on restore failure");check(e.getSuppressed().length==1,"cleanup error suppressed");}check(Driver.get(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==31&&Driver.get(GL30.GL_READ_FRAMEBUFFER_BINDING)==32,"FBOs restored despite cleanup fault");check(Arrays.equals(Driver.clear,new float[]{.12f,.24f,.36f,.48f}),"later groups restored despite earlier cleanup failure");
  Driver.init();Driver.fault="glGetInteger";Driver.faultUnit=GL13.GL_TEXTURE2;try{new GLState();throw new AssertionError("capture should fail");}catch(IllegalStateException expected){}check(Driver.get(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE0+5,"partial capture restores active texture");
  Driver.init();GLState s=new GLState();mutate();Driver.fault="glBindFramebuffer";try{s.close();throw new AssertionError("restore should fail");}catch(IllegalStateException expected){}check(!Driver.attachments.containsKey("999:"+GL30.GL_DEPTH_ATTACHMENT),"failed FBO binding never mutates wrong framebuffer attachment");check(Driver.get(GL30.GL_READ_FRAMEBUFFER_BINDING)==32,"read framebuffer restored after draw failure");
  Driver.init();before=Driver.snapshot();try(GLState levelState=new GLState()){Driver.attach(GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE,201,3,0);}check(before.equals(Driver.snapshot()),"same texture at different mip level restored");
  Driver.init();Driver.attach(GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE,201,2,GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X);before=Driver.snapshot();try(GLState cubeState=new GLState()){Driver.attach(GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE,201,2,GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_X);}check(before.equals(Driver.snapshot()),"same texture at different cube face restored");
  System.out.println("PASS "+checks+" render-state checks; exact GLState source, fault-injecting GL fixture; no GPU");
 }
}
''')
cp=str(JAR)+':'+str(LWJGL)
proc=subprocess.run([str(J8/'javac'),'-source','8','-target','8','-cp',cp,'-d',str(CLASSES)]+[str(p) for p in SRC.rglob('*.java')],text=True,capture_output=True)
(OUT/'compile.log').write_text(proc.stdout+proc.stderr)
if proc.returncode:print(proc.stderr);raise SystemExit(proc.returncode)
for jdk in [J8,J21]:
 proc=subprocess.run([str(jdk/'java'),'-Xverify:all','-cp',str(CLASSES)+':'+cp,'fixture.RenderStateProbe'],text=True,capture_output=True)
 (OUT/(jdk.parent.name+'.log')).write_text(proc.stdout+proc.stderr);print(jdk.parent.name,proc.stdout,proc.stderr)
 if proc.returncode:raise SystemExit(proc.returncode)
(OUT/'SOURCE-SHA256.json').write_text(json.dumps(manifest,indent=2)+'\n')
