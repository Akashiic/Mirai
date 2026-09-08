#!/usr/bin/env python3
"""Compile the exact atlas coordinator with explicit registry/pool fixtures; no GPU."""
from pathlib import Path
import subprocess,hashlib,json
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT.parent;OUT=WORK/'stabilization-0.1.3-evidence/rendering/atlas';SRC=OUT/'src';CLASSES=OUT/'classes'
SRC.mkdir(parents=True,exist_ok=True);CLASSES.mkdir(exist_ok=True)
def write(pkg,name,code):
 p=SRC/Path(pkg.replace('.','/'))/(name+'.java');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package '+pkg+';\n'+code)
p=ROOT/'coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/AbstractBlockTextureAtlas.java'
write('com.seibel.distanthorizons.core.render','AbstractBlockTextureAtlas',p.read_text().split(';',1)[1])
write('com.seibel.distanthorizons.core.dataObjects.render.textures','BlockTextureRegistry',r'''
public final class BlockTextureRegistry {
 public static final BlockTextureRegistry INSTANCE=new BlockTextureRegistry();public static final int TILE_HEIGHT_AND_WIDTH=2,TILE_BYTE_COUNT=16;
 public int resets;private boolean pending=true;public int getTileCount(){return 2;}
 public PendingTiles getAndClearPendingUploadTiles(){if(!pending)return null;pending=false;return new PendingTiles();}
 public void resetPendingUploads(){pending=true;resets++;}
 public static final class PendingTiles {public final int firstTileId=0;public final byte[][] tilePixels={new byte[16],new byte[16]};}
}
''')
write('com.seibel.distanthorizons.core.util.objects.pooling','PhantomArrayList',r'''
import java.nio.ByteBuffer; public final class PhantomArrayList {
 public static class PhantomArrayListCheckout implements AutoCloseable {public ByteBuffer getByteBuffer(int i,int n){return ByteBuffer.allocate(n);} public void close(){}}
 public static class PhantomArrayListPool {public PhantomArrayListPool(String n){}public PhantomArrayListCheckout checkoutByteBuffers(int n){return new PhantomArrayListCheckout();}}
}
''')
write('fixture','AtlasProbe',r'''
import java.nio.ByteBuffer;import com.seibel.distanthorizons.core.render.AbstractBlockTextureAtlas;import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
public final class AtlasProbe extends AbstractBlockTextureAtlas {
 String fail;boolean cleanupFails;int setup,write,complete,abort;final RuntimeException primary=new RuntimeException("injected upload failure");
 protected void tryCreateOrResize(int w,int h){if("resize".equals(fail))throw primary;}
 protected void beforeWriteToTexture(){setup++;if("before".equals(fail))throw primary;}
 protected void writeToTexture(ByteBuffer b,int x,int y,int w,int h){write++;if("write".equals(fail))throw primary;}
 protected void afterWriteToTexture(){complete++;if("after".equals(fail))throw primary;}
 protected void abortWriteToTexture(){abort++;if(cleanupFails)throw new IllegalStateException("injected restore failure");}
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public static void main(String[] args){
  for(String point:new String[]{"before","write","after"}){BlockTextureRegistry.INSTANCE.resetPendingUploads();AtlasProbe a=new AtlasProbe();a.fail=point;int resets=BlockTextureRegistry.INSTANCE.resets;
   try{a.uploadPendingTiles();throw new AssertionError("expected fault");}catch(RuntimeException e){check(e==a.primary,"primary preserved at "+point);}
   check(a.abort==1,"restore once at "+point);check(a.uploadedTileCount==0,"failed upload not published");check(a.complete==("after".equals(point)?1:0),"mip/completion only after writes");check(BlockTextureRegistry.INSTANCE.resets>resets,"pending batch retained on failure");
   a.fail=null;int writes=a.write;a.uploadPendingTiles();check(a.write==writes+2&&a.uploadedTileCount==2,"explicit retry uploads full batch");int ab=a.abort;a.uploadPendingTiles();check(a.abort==ab,"no work when synchronized");
  }
  AtlasProbe a=new AtlasProbe();a.fail="resize";try{a.uploadPendingTiles();throw new AssertionError();}catch(RuntimeException e){check(e==a.primary,"resize error preserved");}check(!a.atlasCreated,"failed resize not initialized");
  BlockTextureRegistry.INSTANCE.resetPendingUploads();a=new AtlasProbe();a.fail="write";a.cleanupFails=true;try{a.uploadPendingTiles();throw new AssertionError();}catch(RuntimeException e){check(e==a.primary&&e.getSuppressed().length==1,"secondary restoration error suppressed");}
  System.out.println("PASS "+checks+" atlas-coordinator checks; exact source, registry/pool fixtures; no GPU");
 }
}
''')
j8=WORK/'toolchains/jdk8/bin'
r=subprocess.run([str(j8/'javac'),'-source','8','-target','8','-d',str(CLASSES)]+[str(p) for p in SRC.rglob('*.java')],text=True,capture_output=True);(OUT/'compile.log').write_text(r.stdout+r.stderr)
if r.returncode:print(r.stderr);raise SystemExit(r.returncode)
for v in ['jdk8','jdk21']:
 r=subprocess.run([str(WORK/'toolchains'/v/'bin/java'),'-Xverify:all','-cp',str(CLASSES),'fixture.AtlasProbe'],text=True,capture_output=True);(OUT/(v+'.log')).write_text(r.stdout+r.stderr);print(v,r.stdout,r.stderr)
 if r.returncode:raise SystemExit(r.returncode)
(OUT/'SOURCE-SHA256.json').write_text(json.dumps({str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()},indent=2)+'\n')
