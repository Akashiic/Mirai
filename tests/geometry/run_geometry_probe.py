#!/usr/bin/env python3
"""Actual pure helper and exact legacy wrapper method bodies, with declared Minecraft doubles."""
import hashlib,json,math,os,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]
BASE=ROOT.parent
OUT=pathlib.Path(os.environ.get('MIRAI_GEOMETRY_EVIDENCE',BASE/'homologation-0.1.4-evidence/geometry'))
GEN=OUT/'generated'; GEN.mkdir(parents=True,exist_ok=True)
helper=ROOT/'common/src/main/java/com/seibel/distanthorizons/common/util/VanillaChunkCoverage.java'
wrapper=ROOT/'common/src/main/java/com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.java'
text=wrapper.read_text()
start=text.index('\tprivate int getLegacyHandoffDistance()')
end=text.index('\n\t#endif',start)
methods=text[start:end]
old=subprocess.run(['git','show','4ce7a1290c62fa9fea6197812b5b342aedf2effc:common/src/main/java/com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.java'],cwd=ROOT,text=True,capture_output=True,check=True).stdout
startold=old.index('\t\tif (ANGELICA_ACCESSOR != null)',old.index('public int getRenderDistance()'))
endold=old.index('\t\treturn MC.gameSettings.renderDistanceChunks;',startold)+len('\t\treturn MC.gameSettings.renderDistanceChunks;')
oldbody=old[startold:endold].replace('\t\t#endif','')
fixture=r'''
import com.seibel.distanthorizons.common.util.VanillaChunkCoverage;
interface IChunkProvider { Chunk provideChunk(int x,int z); }
class Chunk { int xPosition,zPosition; boolean isChunkLoaded=true; Chunk(int x,int z){xPosition=x;zPosition=z;} }
class EmptyChunk extends Chunk { EmptyChunk(){super(0,0);} }
class ChunkProviderClient implements IChunkProvider {
 int radius=9,cx,cz,calls; boolean alwaysMissing,wrongCoordinates,notLoaded;
 public boolean chunkExists(int x,int z){throw new AssertionError("chunkExists must not be used");}
 public Chunk provideChunk(int x,int z){
  calls++;
  if(alwaysMissing || Math.abs((long)x-cx)>radius || Math.abs((long)z-cz)>radius) return new EmptyChunk();
  Chunk c=new Chunk(wrongCoordinates?x+1:x,z); c.isChunkLoaded=!notLoaded; return c;
 }
}
class UnknownProvider implements IChunkProvider { public Chunk provideChunk(int x,int z){throw new AssertionError("unknown provider queried");} }
class SubclassProvider extends ChunkProviderClient { public Chunk provideChunk(int x,int z){throw new AssertionError("overridden provider queried");} }
class World { IChunkProvider provider=new ChunkProviderClient(); int gets; IChunkProvider getChunkProvider(){gets++;return provider;} }
class Entity { double posX,posZ; int ticksExisted; }
class Settings { int renderDistanceChunks=32; }
class Minecraft {
 Settings gameSettings=new Settings(); World theWorld=new World(); Entity thePlayer=new Entity(),renderViewEntity=thePlayer;
 boolean owner=true; boolean isCallingFromMinecraftThread(){return owner;}
}
class MathHelper { static int floor_double(double x){return (int)Math.floor(x);} }
class Bridge {
 static final Minecraft MC=new Minecraft();
 static final Object ANGELICA_ACCESSOR=new Object();
 static final VanillaChunkCoverage LEGACY_CHUNK_COVERAGE=new VanillaChunkCoverage();
 static final VanillaChunkCoverage.ChunkProbe LEGACY_CHUNK_PROBE=Bridge::isLegacyChunkReceived;
 public int getRenderDistance(){return getLegacyHandoffDistance();}
 __METHODS__
}
public class LegacyBridgeProbe {
 static int assertions;
 static void eq(int expected,int actual,String why){assertions++;if(expected!=actual)throw new AssertionError(why+": expected "+expected+", got "+actual);}
 static void yes(boolean result,String why){assertions++;if(!result)throw new AssertionError(why);}
 public static void main(String[] args){
  Bridge b=new Bridge(); Minecraft mc=Bridge.MC;
  if(args.length>0){eq(7,b.getRenderDistance(),"0.1.3 slider32/servercoverage9 negative control");return;}
  ChunkProviderClient p=(ChunkProviderClient)mc.theWorld.provider;
  eq(7,b.getRenderDistance(),"actual wrapper slider32/coverage9");
  int calls=p.calls;
  eq(7,b.getRenderDistance(),"same-frame reuse");eq(calls,p.calls,"provider not queried again");
  String debug=b.getLegacyHandoffDebugString();yes(debug.contains("configured 32, received 9, effective 7"),"bounded numeric diagnostic");
  eq(calls,p.calls,"F3 reuses sample");
  p.radius=4;mc.thePlayer.ticksExisted++;eq(2,b.getRenderDistance(),"shrink next tick");
  mc.renderViewEntity.posX=-0.5;eq(1,b.getRenderDistance(),"negative camera flooring and key invalidation");
  mc.gameSettings.renderDistanceChunks=2;eq(0,b.getRenderDistance(),"config same tick");
  mc.gameSettings.renderDistanceChunks=32;mc.renderViewEntity.posX=0;
  mc.theWorld=new World();eq(7,b.getRenderDistance(),"new WorldClient same tick and camera");
  p=new ChunkProviderClient();p.radius=5;mc.theWorld.provider=p;eq(3,b.getRenderDistance(),"new provider same world/tick");
  p.alwaysMissing=true;mc.thePlayer.ticksExisted++;eq(0,b.getRenderDistance(),"EmptyChunk sentinel cannot claim coverage");
  p.alwaysMissing=false;mc.thePlayer.ticksExisted++;eq(3,b.getRenderDistance(),"real air chunks accepted");
  p.wrongCoordinates=true;mc.thePlayer.ticksExisted++;eq(0,b.getRenderDistance(),"wrong-coordinate placeholder rejected");
  p.wrongCoordinates=false;p.notLoaded=true;mc.thePlayer.ticksExisted++;eq(0,b.getRenderDistance(),"unloaded chunk rejected");
  p.notLoaded=false;mc.theWorld.provider=new UnknownProvider();eq(0,b.getRenderDistance(),"unknown provider never queried");
  yes(b.getLegacyHandoffDebugString().contains("received unknown, effective 0"),"unknown coverage diagnostic");
  mc.theWorld.provider=new SubclassProvider();eq(0,b.getRenderDistance(),"subclass unknown loading policy rejected");
  mc.theWorld.provider=null;eq(0,b.getRenderDistance(),"null provider");
  mc.theWorld=new World();int gets=mc.theWorld.gets;mc.owner=false;
  eq(0,b.getRenderDistance(),"off-owner conservative result");eq(gets,mc.theWorld.gets,"off-owner did not read world/provider map");
  mc.owner=true;mc.theWorld=null;eq(0,b.getRenderDistance(),"null world");
  mc.theWorld=new World();mc.thePlayer=null;eq(0,b.getRenderDistance(),"null player");
  mc.thePlayer=new Entity();mc.renderViewEntity=null;eq(0,b.getRenderDistance(),"null camera");
  mc.renderViewEntity=mc.thePlayer;mc.theWorld.provider=new ChunkProviderClient();
  for(int setting:new int[]{0,-1,1,2,Integer.MIN_VALUE}){mc.gameSettings.renderDistanceChunks=setting;eq(0,b.getRenderDistance(),"small/invalid config "+setting);}
  mc.gameSettings.renderDistanceChunks=Integer.MAX_VALUE; p=(ChunkProviderClient)mc.theWorld.provider;p.radius=Integer.MAX_VALUE;
  eq(30,b.getRenderDistance(),"huge config bound");yes(Bridge.LEGACY_CHUNK_COVERAGE.getProbeCount()<=4225,"actual bridge query cap");
  System.out.println("LegacyBridgeProbe: PASS "+assertions+" assertions; Minecraft classes are explicit doubles");
 }
}
'''
(GEN/'LegacyBridgeProbe.java').write_text(fixture.replace('__METHODS__',methods))
negative=fixture.replace('__METHODS__',methods).replace('public int getRenderDistance(){return getLegacyHandoffDistance();}','public int getRenderDistance(){'+oldbody+'}')
NEG=GEN/'baseline';NEG.mkdir(exist_ok=True)
(NEG/'LegacyBridgeProbe.java').write_text(negative)
results={'runtime':[],'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [helper,wrapper,ROOT/'tests/geometry/VanillaChunkCoverageProbe.java']},'scope':'Actual helper and exact production wrapper method bodies. Explicit Minecraft doubles; no game or OpenGL context.'}
for version in ['jdk8','jdk21']:
    java=BASE/'toolchains'/version/'bin'
    classes=GEN/version;classes.mkdir(exist_ok=True)
    cmd=[str(java/'javac'),'-source','8','-target','8','-d',str(classes),str(helper),str(ROOT/'tests/geometry/VanillaChunkCoverageProbe.java'),str(GEN/'LegacyBridgeProbe.java')]
    build=subprocess.run(cmd,text=True,capture_output=True)
    (OUT/(version+'-compile.log')).write_text(build.stdout+build.stderr)
    if build.returncode: raise RuntimeError(build.stdout+build.stderr)
    for test in ['VanillaChunkCoverageProbe','LegacyBridgeProbe']:
        run=subprocess.run([str(java/'java'),'-Xverify:all','-cp',str(classes),test],text=True,capture_output=True)
        (OUT/(version+'-'+test+'.log')).write_text(run.stdout+run.stderr)
        results['runtime'].append({'java':version,'test':test,'exit_code':run.returncode,'stdout':run.stdout.strip()})
        if run.returncode: raise RuntimeError(run.stdout+run.stderr)
    negclasses=NEG/version;negclasses.mkdir(exist_ok=True)
    compile_neg=subprocess.run([str(java/'javac'),'-source','8','-target','8','-d',str(negclasses),str(helper),str(NEG/'LegacyBridgeProbe.java')],text=True,capture_output=True)
    if compile_neg.returncode: raise RuntimeError(compile_neg.stderr)
    run=subprocess.run([str(java/'java'),'-Xverify:all','-cp',str(negclasses),'LegacyBridgeProbe','baseline-negative'],text=True,capture_output=True)
    (OUT/(version+'-baseline-negative.log')).write_text(run.stdout+run.stderr)
    yes=run.returncode!=0 and 'expected 7, got 30' in run.stderr
    results['runtime'].append({'java':version,'test':'baseline-0.1.3-negative','detected_expected_failure':yes,'exit_code':run.returncode})
    if not yes: raise RuntimeError('baseline negative did not reproduce: '+run.stdout+run.stderr)
scenarios=[]
for aspect in [16/9,4/3,9/16]:
    divisor=math.sqrt(1+math.tan(math.radians(35))**2*(aspect**2+1))
    for configured in [30,7]:
        for manual in [False,True]:
            multiplier=1.0 if manual else (.9 if configured>10 else .8)
            near=max(1,16*configured*multiplier)/divisor
            clip=near+16;complete=1.5*clip
            assert math.isfinite(clip) and math.isfinite(complete)
            scenarios.append({'aspect':aspect,'effective_chunks':configured,'manual_overdraw_1':manual,'clip':clip,'dither_complete':complete,'inside_conservative_9chunk_square_edge_144':complete<=144})
for aspect in [16/9,4/3]:
    row=next(r for r in scenarios if r['aspect']==aspect and r['effective_chunks']==7 and not r['manual_overdraw_1'])
    assert row['dither_complete']<144
results['numeric_scenarios']=scenarios
results['numeric_limit']='At received9/effective7, portrait9:16/manual1.0 completes dithering beyond144blocks. This candidate fixes configured-vs-received distance; it does not certify zero gaps for every aspect/manual setting or incomplete meshes/cache.'
# Compile the exact method bodies against the actual Forge 1614 MCP API as well.
forge=next(p for p in (BASE/'gradle-cache/caches/unimined').rglob('*.jar') if p.name.endswith('+fixForge+fixInners-mcp-at+64a839f.jar'))
real="""import com.seibel.distanthorizons.common.util.VanillaChunkCoverage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.util.MathHelper;
class LegacyBridgeExactForge {
 static final Minecraft MC=Minecraft.getMinecraft();
 static final VanillaChunkCoverage LEGACY_CHUNK_COVERAGE=new VanillaChunkCoverage();
 static final VanillaChunkCoverage.ChunkProbe LEGACY_CHUNK_PROBE=LegacyBridgeExactForge::isLegacyChunkReceived;
"""+methods+"\n}\n"
real_source=GEN/'LegacyBridgeExactForge.java';real_source.write_text(real)
real_results=[]
for version in ['jdk8','jdk21']:
    classes=GEN/('real-forge-'+version);classes.mkdir(exist_ok=True)
    build=subprocess.run([str(BASE/'toolchains'/version/'bin/javac'),'-source','8','-target','8','-cp',str(forge),'-d',str(classes),str(helper),str(real_source)],text=True,capture_output=True)
    (OUT/('real-forge-'+version+'-compile.log')).write_text(build.stdout+build.stderr)
    if build.returncode: raise RuntimeError(build.stderr)
    real_results.append({'java':version,'exit_code':build.returncode,'scope':'exact production legacy method bodies compiled against real Forge 1614 MCP API; no game run'})
(OUT/'REAL-FORGE-COMPILE.json').write_text(json.dumps({'passed':True,'forge_jar':str(forge),'forge_sha256':hashlib.sha256(forge.read_bytes()).hexdigest(),'results':real_results},indent=2)+'\n')
results['real_forge_compilation']=real_results
results['passed']=True
(OUT/'RESULTS.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
