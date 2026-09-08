#!/usr/bin/env python3
"""Compile complete render-load production classes against strict resource/queue doubles.

Checks buffer lifetime across asynchronous work, exceptions and section cancellation.
No Minecraft/native GL. --baseline-commit requires the known regressions to fail
as assertions against unchanged source, while the normal-success control passes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [Path('coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodRenderSection.java'),
           Path('coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodBufferContainer.java')]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', required=True, type=Path)
parser.add_argument('--javac', required=True)
parser.add_argument('--java', required=True)
parser.add_argument('--baseline-commit')
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)

FIXTURES = {
'org.jetbrains.annotations.Nullable': '@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE,java.lang.annotation.ElementType.METHOD,java.lang.annotation.ElementType.PARAMETER,java.lang.annotation.ElementType.FIELD,java.lang.annotation.ElementType.LOCAL_VARIABLE}) public @interface Nullable {}',
'javax.annotation.WillNotClose': 'public @interface WillNotClose {}',
'com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency': 'public enum EDhApiTransparency { COMPLETE }',
'com.seibel.distanthorizons.core.config.Config': '''public class Config {
 public static class Value<T>{T value;public Value(T v){value=v;}public T get(){return value;}}
 public static class Client {public static class Advanced {
  public static class Graphics {public static class Quality {public static Value<com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency> transparency=new Value<>(com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency.COMPLETE);}}
  public static class Debugging {public static class DebugWireframe {public static Value<Boolean> showRenderSectionStatus=new Value<>(false);}}
 }}
}''',
'com.seibel.distanthorizons.core.logging.DhLogger': '''public class DhLogger {
 public void error(String s,Throwable t){fixture.Driver.errors++;} public void error(String s){fixture.Driver.errors++;}
}''',
'com.seibel.distanthorizons.core.logging.DhLoggerBuilder': 'public class DhLoggerBuilder {public DhLogger build(){return new DhLogger();}}',
'com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos': 'public class DhBlockPos {public DhBlockPos(int x,int y,int z){}}',
'com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D': 'public class DhBlockPos2D {public DhBlockPos2D(DhBlockPos p){}}',
'com.seibel.distanthorizons.core.pos.DhSectionPos': '''public class DhSectionPos {
 public static final byte SECTION_MINIMUM_DETAIL_LEVEL=6;
 public static String toString(long p){return Long.toString(p);} public static long getAdjacentPos(long p,com.seibel.distanthorizons.core.enums.EDhDirection d){return p+d.ordinal()+1;}
 public static byte getDetailLevel(long p){return 6;}public static int getMinCornerBlockX(long p){return 0;}public static int getMinCornerBlockZ(long p){return 0;}
}''',
'com.seibel.distanthorizons.core.enums.EDhDirection': '''public enum EDhDirection {
 DOWN(-1),UP(-1),NORTH(0),SOUTH(1),EAST(2),WEST(3);public final int compassIndex;EDhDirection(int c){compassIndex=c;}
 public static final EDhDirection[] CARDINAL_COMPASS={NORTH,SOUTH,EAST,WEST};public EDhDirection opposite(){return this;}
}''',
'com.seibel.distanthorizons.core.level.IDhClientLevel': '''public interface IDhClientLevel {com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper getClientLevelWrapper();}''',
'com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper': '''public interface IClientLevelWrapper {int getMinHeight();int getMaxHeight();com.seibel.distanthorizons.core.level.IDhClientLevel getDhLevel();}''',
'com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper': '''public class IMinecraftClientWrapper {public com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos getPlayerBlockPos(){return new com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos(0,0,0);}}''',
'com.seibel.distanthorizons.core.render.renderer.IDebugRenderable': 'public interface IDebugRenderable {void debugRender(AbstractDebugWireframeRenderer renderer);}',
'com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer': '''public class AbstractDebugWireframeRenderer {
 public void register(Object a,Object b){}public void unregister(Object a,Object b){}public void makeParticle(Object a){}public void renderBox(Object a){}
 public static class Box {public Box(long p,float a,float b,double c,java.awt.Color d){}}
 public static class BoxParticle {public BoxParticle(Box b,double x,float y){}}
}''',
'com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree': '''public class LodQuadTree {public byte calcExpectedDetailLevel(com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D p,long s){return 0;}}''',
'com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2': '''public class FullDataSourceV2 implements AutoCloseable {public void close(){fixture.Driver.sourceCloses++;}}''',
'com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2': '''public class FullDataSourceProviderV2 {
 public com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2 get(long p){fixture.Driver.dbReads++;return new com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2();}
 public com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2 getAdjForDirection(long p,com.seibel.distanthorizons.core.enums.EDhDirection d){return get(p);}
}''',
'com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource': 'public class ColumnRenderSource implements AutoCloseable {public void close(){}}',
'com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer': '''public class FullDataToRenderDataTransformer {
 public static com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource transformFullDataToRenderSource(com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2 data,com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper w){return new com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource();}
}''',
'com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBufferBuilder': '''public class ColumnRenderBufferBuilder {
 public static void makeLodRenderData(LodQuadBuilder b,com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource r,com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper w,com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource[] a,boolean[] s){
 b.quads++;if(fixture.Driver.failGeometry)throw new IllegalStateException("geometry");if(fixture.Driver.afterBuild!=null)fixture.Driver.afterBuild.run();}
}''',
'com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder': '''public class LodQuadBuilder implements AutoCloseable {
 public static final int BYTES_PER_VERTEX=16;public int quads;
 public static final com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool ARRAY_LIST_POOL=new com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool("vertex");
 static final ThreadLocal<LodQuadBuilder> LOCAL=ThreadLocal.withInitial(LodQuadBuilder::new);
 public static LodQuadBuilder getBuilder(boolean b,com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper w){fixture.Driver.builderGets++;return LOCAL.get();}
 public java.util.ArrayList<java.nio.ByteBuffer> makeOpaqueVertexBuffers(com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout c){if(fixture.Driver.failVertices)throw new IllegalStateException("vertices");return buffers(c);}
 public java.util.ArrayList<java.nio.ByteBuffer> makeTransparentVertexBuffers(com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout c){java.util.ArrayList<java.nio.ByteBuffer> b=buffers(c);if(fixture.Driver.beforeUpload!=null)fixture.Driver.beforeUpload.run();return b;}
 java.util.ArrayList<java.nio.ByteBuffer> buffers(com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout c){java.util.ArrayList<java.nio.ByteBuffer> b=new java.util.ArrayList<>();java.nio.ByteBuffer v=c.getByteBuffer(0,64);v.putInt(0,quads);b.add(v);return b;}
 public void close(){fixture.Driver.builderCloses++;quads=0;}
}''',
'com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.IndexBufferBuilder': '''public class IndexBufferBuilder {
 public static java.nio.ByteBuffer populateBuffer(com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout c,int i,int count){if(fixture.Driver.failIndices)throw new IllegalStateException("indices");return c.getByteBuffer(i,Math.max(4,count*24));}
}''',
'com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool': '''public class PhantomArrayListPool {
 public final String name;public PhantomArrayListPool(String n){name=n;}
 public PhantomArrayListCheckout checkoutByteBuffers(int count){return fixture.Driver.checkout(name);}
}''',
'com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout': '''public class PhantomArrayListCheckout implements AutoCloseable {
 public final int id;public final String pool;public volatile boolean closed;public int closes;
 public PhantomArrayListCheckout(int i,String p){id=i;pool=p;}
 public java.nio.ByteBuffer getByteBuffer(int i,int size){fixture.Driver.check(!closed,"read_closed_checkout");java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(size);fixture.Driver.owners.put(b,this);return b;}
 public void close(){closes++;fixture.Driver.check(!closed,"double_return_lease");closed=true;}
}''',
'com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper': '''public interface IVertexBufferWrapper {void uploadVertexBuffer(java.nio.ByteBuffer b,int n);void uploadIndexBuffer(java.nio.ByteBuffer b,int n);void close();}''',
'com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper': 'public interface ILodContainerUniformBufferWrapper {void close();}',
'com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory': '''public class IWrapperFactory {
 public com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper createVboWrapper(String n){if(fixture.Driver.failSetup)throw new IllegalStateException("setup");return new fixture.Driver.Vbo();}
 public com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper createLodContainerUniformWrapper(){fixture.Driver.Uniform u=new fixture.Driver.Uniform();fixture.Driver.uniforms.add(u);return u;}
}''',
'com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition': 'public class AbstractDhRenderApiDefinition {public boolean useSingleIbo(){return false;}}',
'com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector': '''public class SingletonInjector {
 public static final SingletonInjector INSTANCE=new SingletonInjector();public <T>T get(Class<T> c){try{return c.newInstance();}catch(Exception e){throw new AssertionError(e);}}
}''',
'com.seibel.distanthorizons.core.util.ExceptionUtil': '''public class ExceptionUtil {public static boolean isShutdownException(Throwable e){while(e.getCause()!=null)e=e.getCause();return e instanceof InterruptedException||e instanceof java.util.concurrent.CancellationException;}}''',
'com.seibel.distanthorizons.core.util.LodUtil': 'public class LodUtil {public static void assertTrue(boolean b,String s){fixture.Driver.check(b,s);}}',
'com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker': '''public class PriorityTaskPicker {public static class Executor {
 public final java.util.concurrent.LinkedBlockingQueue<Runnable> tasks=new java.util.concurrent.LinkedBlockingQueue<>();
 public boolean isTerminated(){return false;}public void execute(Runnable r){tasks.add(r);}public void remove(Runnable r){tasks.remove(r);}
}}''',
'com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil': '''public class ThreadPoolUtil {public static final PriorityTaskPicker.Executor LOADER=new PriorityTaskPicker.Executor();public static PriorityTaskPicker.Executor getRenderLoadingExecutor(){return LOADER;}}''',
'com.seibel.distanthorizons.core.render.RenderThreadTaskHandler': '''public class RenderThreadTaskHandler {
 public static final RenderThreadTaskHandler INSTANCE=new RenderThreadTaskHandler();
 public void queueRunningOnRenderThread(String n,Runnable r){
  if(n.endsWith("Setup")&&fixture.Driver.rejectSetup)throw new java.util.concurrent.RejectedExecutionException("setup");
  if(n.endsWith("Upload")&&++fixture.Driver.uploadSubmissions==fixture.Driver.rejectUploadAt)throw new java.util.concurrent.RejectedExecutionException("upload");
  fixture.Driver.renderTasks.add(r);}
}''',
}

DRIVER = r'''package fixture;
import java.nio.*;import java.util.*;import java.util.concurrent.*;
import com.seibel.distanthorizons.core.render.QuadTree.*;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.*;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.*;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.*;
public final class Driver {
 public static volatile boolean failGeometry,failVertices,failIndices,failSetup,failUpload;
 public static volatile Runnable afterBuild,beforeUpload;
 public static int errors,dbReads,sourceCloses,builderGets,builderCloses,acquisitions,failAcquireAt,uploadCalls,uploadSubmissions,rejectUploadAt;
 public static boolean rejectSetup;
 public static boolean expectRejectedSubmissionSettlement;
 public static final List<PhantomArrayListCheckout> leases=Collections.synchronizedList(new ArrayList<>());
 public static final Map<ByteBuffer,PhantomArrayListCheckout> owners=Collections.synchronizedMap(new IdentityHashMap<>());
 public static final List<Uniform> uniforms=new ArrayList<>();
 public static final LinkedBlockingQueue<Runnable> renderTasks=new LinkedBlockingQueue<>();
 public static volatile CountDownLatch uploadEntered,uploadRelease;
 public static void check(boolean b,String label){if(!b)throw new AssertionError("ASSERT "+label);}
 public static PhantomArrayListCheckout checkout(String pool){int id=++acquisitions;if(id==failAcquireAt)throw new IllegalStateException("checkout");PhantomArrayListCheckout c=new PhantomArrayListCheckout(id,pool);leases.add(c);return c;}
 public static final class Uniform implements ILodContainerUniformBufferWrapper {public int closes;public void close(){closes++;check(closes==1,"uniform_double_close");}}
 public static final class Vbo implements IVertexBufferWrapper {
  boolean closed;
  public void uploadVertexBuffer(ByteBuffer b,int n){consume(b);check(b.getInt(0)==1,"geometry_from_failed_prior_section");}
  public void uploadIndexBuffer(ByteBuffer b,int n){consume(b);}
  void consume(ByteBuffer b){check(!closed,"upload_closed_vbo");PhantomArrayListCheckout c=owners.get(b);check(c!=null&&!c.closed,"lease_live_at_upload_start");
   CountDownLatch entered=uploadEntered,release=uploadRelease;if(entered!=null){uploadEntered=null;entered.countDown();try{check(release.await(5,TimeUnit.SECONDS),"release_upload_timeout");}catch(InterruptedException e){throw new AssertionError(e);}}
   check(!c.closed,"lease_live_while_native_consumer_active");uploadCalls++;if(failUpload){failUpload=false;throw new IllegalStateException("upload");}}
  public void close(){check(!closed,"vbo_double_close");closed=true;}
 }
 public static final class Level implements com.seibel.distanthorizons.core.level.IDhClientLevel,com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper {
  public volatile boolean attached=true;public com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper getClientLevelWrapper(){return this;}
  public com.seibel.distanthorizons.core.level.IDhClientLevel getDhLevel(){return attached?this:null;}public int getMinHeight(){return 0;}public int getMaxHeight(){return 256;}
 }
 static LodRenderSection section(Level level){return new LodRenderSection(6,new LodQuadTree(),level,new com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2());}
 static Thread worker(LodRenderSection section)throws Exception {check(section.uploadRenderDataToGpuAsync(),"admitted_build");Runnable task=ThreadPoolUtil.LOADER.tasks.poll(2,TimeUnit.SECONDS);check(task!=null,"loader_task");Thread t=new Thread(task,"fixture-loader");t.setDaemon(true);t.start();return t;}
 static void drain(Thread worker)throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(worker.isAlive()||!renderTasks.isEmpty()){
   if(System.nanoTime()-end>=0&&expectRejectedSubmissionSettlement)check(!renderTasks.isEmpty(),"submission_failure_left_parent_pending_after_consumers_drained");
   check(System.nanoTime()-end<0,"worker_did_not_terminate");Runnable r=renderTasks.poll(10,TimeUnit.MILLISECONDS);if(r!=null)r.run();}
  worker.join(1000);
 }
 static void drainQueue(){Runnable r;while((r=renderTasks.poll())!=null)r.run();}
 static void waitForSetup(Thread worker)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(renderTasks.isEmpty()&&worker.isAlive()&&System.nanoTime()-end<0)Thread.yield();check(!renderTasks.isEmpty(),"setup_queued");}
 static void allReturned(){for(PhantomArrayListCheckout c:leases)check(c.closed&&c.closes==1,"lease_returned_once_"+c.pool+"_"+c.id);}
 static void allLive(){for(PhantomArrayListCheckout c:leases)check(!c.closed,"lease_live_before_consumers_terminal_"+c.pool);}
 static void dispose(LodRenderSection section){section.close();drainQueue();}
 public static void main(String[] args)throws Exception {
  String scenario=args[0];Level level=new Level();LodRenderSection section=section(level);
  if(scenario.startsWith("direct-index-")){
   PhantomArrayListCheckout vertices=new PhantomArrayListCheckout(99,"external");ArrayList<ByteBuffer> a=new ArrayList<>(),b=new ArrayList<>();ByteBuffer av=vertices.getByteBuffer(0,64),bv=vertices.getByteBuffer(1,64);av.putInt(0,1);bv.putInt(0,1);a.add(av);b.add(bv);
   if(scenario.equals("direct-index-build-failure")){
    failIndices=true;try{LodBufferContainer.tryMakeAndUploadBuffersAsync(6,level,a,b);throw new AssertionError("ASSERT expected_index_failure");}catch(IllegalStateException expected){}
   }else{
    failUpload=true;CompletableFuture<LodBufferContainer> result=LodBufferContainer.tryMakeAndUploadBuffersAsync(6,level,a,b);drainQueue();check(result.isCompletedExceptionally(),"upload_failure_settled");
   }
   drainQueue();allReturned();vertices.close();
  }else if(scenario.equals("cancel-result-before-setup")||scenario.equals("cancel-result-active-upload")){
   PhantomArrayListCheckout vertices=new PhantomArrayListCheckout(99,"external");ArrayList<ByteBuffer> a=new ArrayList<>(),b=new ArrayList<>();ByteBuffer av=vertices.getByteBuffer(0,64),bv=vertices.getByteBuffer(1,64);av.putInt(0,1);bv.putInt(0,1);a.add(av);b.add(bv);
   CompletableFuture<LodBufferContainer> result=LodBufferContainer.tryMakeAndUploadBuffersAsync(6,level,a,b);
   Thread active=null;
   if(scenario.endsWith("active-upload")){
    renderTasks.poll().run();uploadEntered=new CountDownLatch(1);CountDownLatch entered=uploadEntered;uploadRelease=new CountDownLatch(1);Runnable work=renderTasks.poll();active=new Thread(work);active.setDaemon(true);active.start();check(entered.await(5,TimeUnit.SECONDS),"upload_entered");
   }
   result.cancel(true);allLive();
   if(active!=null){uploadRelease.countDown();active.join(5000);check(!active.isAlive(),"active_upload_finished");}
   drainQueue();allReturned();vertices.close();for(Uniform u:uniforms)check(u.closes==1,"uniform_released_after_cancel");
  }else if(scenario.equals("cancel-after-dequeue")||scenario.equals("retired-level")){
   check(section.uploadRenderDataToGpuAsync(),"admitted_build");Runnable task=ThreadPoolUtil.LOADER.tasks.poll();
   if(scenario.equals("retired-level"))level.attached=false;else section.close();
   Thread t=new Thread(task);t.setDaemon(true);t.start();drain(t);check(dbReads==0,"cancelled_work_no_db_read");check(acquisitions==0,"cancelled_work_no_lease");dispose(section);
  }else if(scenario.equals("old-buffer-preserved")){
   Thread first=worker(section);drain(first);LodBufferContainer old=section.renderBufferContainer;
   failVertices=true;Thread second=worker(section);drain(second);check(section.renderBufferContainer==old&&old.buffersUploaded,"valid_old_geometry_preserved_on_failure");allReturned();dispose(section);
  }else if(scenario.equals("geometry-cleanup")){
   failGeometry=true;check(section.uploadRenderDataToGpuAsync(),"admitted_first");ThreadPoolUtil.LOADER.tasks.poll().run();check(builderCloses==1,"failed_builder_returned");
   failGeometry=false;check(section.uploadRenderDataToGpuAsync(),"admitted_second");Runnable task=ThreadPoolUtil.LOADER.tasks.poll();
   // Reuse this exact worker thread's ThreadLocal builder; another thread drains uploads.
   Thread drain=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){Runnable r=renderTasks.poll(10,TimeUnit.MILLISECONDS);if(r!=null)r.run();}}catch(InterruptedException expected){}});drain.setDaemon(true);drain.start();task.run();drain.interrupt();drain.join(1000);check(section.canRender(),"second_geometry_published");dispose(section);allReturned();
  }else{
   if(scenario.equals("vertex-failure"))failVertices=true;
   if(scenario.equals("second-vertex-checkout"))failAcquireAt=2;
   if(scenario.equals("first-index-checkout"))failAcquireAt=3;
   if(scenario.equals("second-index-checkout"))failAcquireAt=4;
   if(scenario.equals("index-failure"))failIndices=true;
   if(scenario.equals("setup-failure"))failSetup=true;
   if(scenario.equals("upload-failure"))failUpload=true;
   if(scenario.equals("reject-setup"))rejectSetup=true;
   if(scenario.equals("reject-upload-partial")){rejectUploadAt=2;expectRejectedSubmissionSettlement=true;}
   if(scenario.equals("cancel-after-build"))afterBuild=section::close;
   if(scenario.equals("cancel-before-upload"))beforeUpload=section::close;
   Thread t=worker(section);
   if(scenario.equals("late-publication")){waitForSetup(t);allLive();section.close();allLive();}
   if(scenario.equals("leases-live-until-drained")){waitForSetup(t);allLive();renderTasks.poll().run();waitForSetup(t);renderTasks.poll().run();allLive();}
   drain(t);allReturned();
   if(scenario.equals("success")||scenario.equals("leases-live-until-drained")){check(section.canRender(),"successful_geometry_published");check(uploadCalls==4,"all_payloads_uploaded");}
   if(scenario.equals("late-publication"))check(!section.canRender(),"closed_section_no_late_publication");
   if(scenario.equals("cancel-after-build"))check(acquisitions==0,"cancel_after_build_skips_serialization");
   if(scenario.equals("cancel-before-upload"))check(acquisitions==2&&uploadCalls==0,"cancel_before_upload_skips_gpu_work");
   check(builderCloses==1,"builder_returned_once");dispose(section);if(scenario.equals("idempotent-close"))dispose(section);
  }
  for(Uniform u:uniforms)check(u.closes==1,"uniform_released_once");
  System.out.println("PASS "+scenario+" leases="+leases.size()+" uploads="+uploadCalls+" db_reads="+dbReads);
 }
}
'''

SCENARIOS = ['success', 'leases-live-until-drained', 'vertex-failure', 'second-vertex-checkout',
             'first-index-checkout', 'second-index-checkout', 'index-failure', 'setup-failure', 'upload-failure',
             'cancel-after-dequeue', 'retired-level', 'cancel-after-build', 'cancel-before-upload', 'late-publication',
             'geometry-cleanup', 'cancel-result-before-setup', 'cancel-result-active-upload', 'reject-setup', 'reject-upload-partial',
             'idempotent-close', 'old-buffer-preserved', 'direct-index-build-failure', 'direct-index-upload-failure']
NEGATIVE_ASSERTIONS = {
    'vertex-failure': 'lease_returned_once_vertex_1',
    'second-vertex-checkout': 'lease_returned_once_vertex_1',
    'first-index-checkout': 'lease_returned_once_vertex_1',
    'second-index-checkout': 'lease_returned_once_vertex_1',
    'index-failure': 'lease_returned_once_vertex_1',
    'setup-failure': 'uniform_double_close',
    'upload-failure': 'lease_returned_once_vertex_1',
    'cancel-after-dequeue': 'cancelled_work_no_db_read',
    'retired-level': 'cancelled_work_no_db_read',
    'cancel-after-build': 'cancel_after_build_skips_serialization',
    'cancel-before-upload': 'cancel_before_upload_skips_gpu_work',
    'late-publication': 'closed_section_no_late_publication',
    'geometry-cleanup': 'failed_builder_returned',
    'cancel-result-before-setup': 'uniform_double_close',
    'cancel-result-active-upload': 'vbo_double_close',
    'reject-setup': 'lease_returned_once_vertex_1',
    'reject-upload-partial': 'submission_failure_left_parent_pending_after_consumers_drained',
    'idempotent-close': 'uniform_double_close',
    'old-buffer-preserved': 'lease_returned_once_vertex_5',
    'direct-index-build-failure': 'lease_returned_once_LodBufferContainer_1',
    'direct-index-upload-failure': 'lease_returned_once_LodBufferContainer_1',
}
report = {'scope': __doc__, 'source_hashes': {str(path): hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in SOURCES},
          'baseline_commit': args.baseline_commit, 'runs': []}

def run_suite(label, commit=None):
    output = args.out / label
    sources, classes = output / 'src', output / 'classes'
    sources.mkdir(parents=True, exist_ok=True); classes.mkdir(parents=True, exist_ok=True)
    for name, text in FIXTURES.items():
        package = name.rsplit('.', 1)[0]
        path = sources / (name.replace('.', '/') + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + package + ';\n' + text + '\n', encoding='utf-8')
    driver = sources / 'fixture/Driver.java'; driver.parent.mkdir(parents=True, exist_ok=True); driver.write_text(DRIVER, encoding='utf-8')
    for path in SOURCES:
        content = subprocess.run(['git', 'show', commit + ':' + path.as_posix()], cwd=ROOT, capture_output=True, check=True).stdout if commit else (ROOT / path).read_bytes()
        target = sources / Path(*path.parts[5:])
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
    command = [args.javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(classes)] + [str(path) for path in sources.rglob('*.java')]
    result = subprocess.run(command, capture_output=True, text=True)
    (output / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode:
        print(result.stderr); raise SystemExit('Compilation failed: ' + label)
    for scenario in SCENARIOS:
        try:
            result = subprocess.run([args.java, '-Xverify:all', '-cp', str(classes), 'fixture.Driver', scenario], capture_output=True, text=True, timeout=15)
            text, code = result.stdout + result.stderr, result.returncode
        except subprocess.TimeoutExpired as failure:
            text, code = str(failure), None
        (output / (scenario + '.log')).write_text(text, encoding='utf-8')
        expected_assertion = NEGATIVE_ASSERTIONS.get(scenario) if commit else None
        expected_failure = expected_assertion is not None
        assertion = re.search(r'ASSERT ([A-Za-z0-9_]+)', text)
        passed = (code != 0 and code is not None and assertion is not None and assertion.group(1) == expected_assertion) if expected_failure else code == 0 and ('PASS ' + scenario) in text and assertion is None
        report['runs'].append({'source': label, 'scenario': scenario, 'exit_code': code, 'negative_expected': expected_failure,
                              'expected_assertion': expected_assertion, 'assertion': assertion.group(1) if assertion else None, 'passed': passed})
        print(('PASS ' if passed else 'FAIL ') + label + ' ' + scenario, flush=True)

run_suite('candidate')
if args.baseline_commit:
    run_suite('baseline', args.baseline_commit)
report['passed'] = all(run['passed'] for run in report['runs'])
(args.out / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
raise SystemExit(0 if report['passed'] else 1)
