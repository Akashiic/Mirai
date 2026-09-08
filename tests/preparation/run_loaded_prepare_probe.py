#!/usr/bin/env python3
"""Exercise production resident preparation and command classes against deterministic platform boundaries."""
import json
import pathlib
import subprocess
import sys
import tempfile

repo = pathlib.Path(__file__).resolve().parents[2]
base = repo.parent
out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else base / 'homologation-0.1.5-evidence/preparation'
out.mkdir(parents=True, exist_ok=True)
fixtures = {
'net/minecraft/world/WorldProvider.java': 'package net.minecraft.world; public class WorldProvider { public int dimensionId; }',
'net/minecraft/world/World.java': '''package net.minecraft.world; public class World { public WorldProvider provider=new WorldProvider(); public net.minecraft.world.gen.ChunkProviderServer chunks=new net.minecraft.world.gen.ChunkProviderServer(); public Object getChunkProvider(){ return chunks; } }''',
'net/minecraft/world/WorldServer.java': 'package net.minecraft.world; public class WorldServer extends World { }',
'net/minecraft/world/ChunkCoordIntPair.java': '''package net.minecraft.world; public class ChunkCoordIntPair { public static long chunkXZ2Int(int x,int z){return (x & 0xffffffffL) | ((z & 0xffffffffL)<<32);} }''',
'net/minecraft/world/chunk/Chunk.java': '''package net.minecraft.world.chunk; public class Chunk { public net.minecraft.world.World worldObj; public int xPosition,zPosition; public boolean isTerrainPopulated=true,isLightPopulated=true; public byte[] biomes=new byte[256]; public byte[] getBiomeArray(){return biomes;} }''',
'net/minecraft/world/chunk/EmptyChunk.java': 'package net.minecraft.world.chunk; public class EmptyChunk extends Chunk { }',
'net/minecraft/world/gen/ChunkProviderServer.java': '''package net.minecraft.world.gen; public class ChunkProviderServer { public final ResidentMap id2ChunkMap=new ResidentMap(); public Object loadChunk(int x,int z){throw new AssertionError("generation entrypoint");} public Object provideChunk(int x,int z){throw new AssertionError("generation entrypoint");} public static class ResidentMap extends java.util.HashMap<Long,Object>{public int reads; public Object getValueByKey(long key){reads++;return get(key);}} }''',
'net/minecraftforge/common/DimensionManager.java': '''package net.minecraftforge.common; public class DimensionManager { public static net.minecraft.world.WorldServer current; public static net.minecraft.world.WorldServer getWorld(int id){return current!=null&&current.provider.dimensionId==id?current:null;} }''',
'com/seibel/distanthorizons/core/level/IDhLevel.java': 'package com.seibel.distanthorizons.core.level; public interface IDhLevel { }',
'com/seibel/distanthorizons/common/wrappers/world/ServerLevelWrapper.java': '''package com.seibel.distanthorizons.common.wrappers.world; public class ServerLevelWrapper { public static final ServerLevelWrapper WRAPPER=new ServerLevelWrapper(); public static ServerLevelWrapper getWrapper(net.minecraft.world.WorldServer w){return WRAPPER;} public String getDimensionName(){return "test";} }''',
'com/seibel/distanthorizons/common/wrappers/chunk/ChunkWrapper.java': '''package com.seibel.distanthorizons.common.wrappers.chunk; public class ChunkWrapper { public static int captures; public static boolean fail; public ChunkWrapper(net.minecraft.world.chunk.Chunk c, com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper w){captures++;if(fail)throw new IllegalStateException("capture failure");} public static boolean canSaveChunk(net.minecraft.world.chunk.Chunk c){return c.isTerrainPopulated&&c.isLightPopulated;} }''',
'com/seibel/distanthorizons/core/api/internal/SharedApi.java': '''package com.seibel.distanthorizons.core.api.internal; public class SharedApi { public static final SharedApi INSTANCE=new SharedApi(); public static final DhWorld world=new DhWorld(); public static boolean busy; public static int submitted; public static DhWorld getAbstractDhWorld(){return world;} public static boolean isChunkAtChunkPosAlreadyUpdating(Object w,int x,int z){return busy;} public void applyChunkUpdate(Object c,Object w,boolean wait){submitted++;} public static class DhWorld {public com.seibel.distanthorizons.core.level.IDhLevel level=new com.seibel.distanthorizons.core.level.IDhLevel(){}; public com.seibel.distanthorizons.core.level.IDhLevel getLevel(Object w){return level;}} }''',
'com/seibel/distanthorizons/core/config/eventHandlers/IgnoredDimensionCsvHandler.java': '''package com.seibel.distanthorizons.core.config.eventHandlers; public class IgnoredDimensionCsvHandler { public static final IgnoredDimensionCsvHandler INSTANCE=new IgnoredDimensionCsvHandler(); public boolean ignored; public boolean dimensionNameShouldBeIgnored(String n){return ignored;} }''',
'com/seibel/distanthorizons/core/world/DhApiWorldProxy.java': '''package com.seibel.distanthorizons.core.world; public class DhApiWorldProxy {public static final DhApiWorldProxy INSTANCE=new DhApiWorldProxy(); public boolean readOnly; public boolean tryGetReadOnly(){return readOnly;}}''',
'com/seibel/distanthorizons/core/logging/DhLogger.java': '''package com.seibel.distanthorizons.core.logging; public class DhLogger {public void info(String s){} public void error(String s,Throwable t){} }''',
'com/seibel/distanthorizons/core/logging/DhLoggerBuilder.java': '''package com.seibel.distanthorizons.core.logging; public class DhLoggerBuilder {public DhLogger build(){return new DhLogger();}}''',
'com/seibel/distanthorizons/common/util/threading/ServerThreadTaskHandler.java': '''package com.seibel.distanthorizons.common.util.threading; public class ServerThreadTaskHandler { public static final ServerThreadTaskHandler INSTANCE=new ServerThreadTaskHandler(); public long session=1; public boolean owner=true,reject; public int maxPending; public java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>(); public long getSessionId(){return session;} public boolean isServerThread(){return owner;} public <T> java.util.concurrent.CompletableFuture<T> queueTask(long s,java.util.function.Supplier<T> task){java.util.concurrent.CompletableFuture<T> f=new java.util.concurrent.CompletableFuture<>();if(reject){f.completeExceptionally(new java.util.concurrent.RejectedExecutionException());return f;} queue.add(()->{if(f.isCancelled())return;try{f.complete(task.get());}catch(Throwable t){f.completeExceptionally(t);}});maxPending=Math.max(maxPending,queue.size());return f;} public void drain(){while(!queue.isEmpty())queue.remove().run();} }''',
'net/minecraft/command/ICommandSender.java': '''package net.minecraft.command; public interface ICommandSender {void receive(String text);}''',
'com/seibel/distanthorizons/common/commands/AbstractDhCommand.java': '''package com.seibel.distanthorizons.common.commands; public abstract class AbstractDhCommand {protected static void sendMessage(net.minecraft.command.ICommandSender s,String t){s.receive(t);} }'''
}
probe=r'''
import com.seibel.distanthorizons.common.util.LegacyLoadedTerrainPreparation;
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import com.seibel.distanthorizons.common.commands.PrepareLoadedCommand;
import net.minecraft.world.*;
import net.minecraft.world.chunk.*;
import net.minecraftforge.common.DimensionManager;
public class LoadedPrepareProbe {
 static final LegacyLoadedTerrainPreparation p=LegacyLoadedTerrainPreparation.INSTANCE;
 static final ServerThreadTaskHandler h=ServerThreadTaskHandler.INSTANCE;
 static int cases;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static WorldServer reset(){h.owner=true;p.stop();h.drain();h.session++;h.reject=false;h.maxPending=0;ChunkWrapper.captures=0;ChunkWrapper.fail=false;SharedApi.submitted=0;SharedApi.busy=false;SharedApi.world.level=new com.seibel.distanthorizons.core.level.IDhLevel(){};DhApiWorldProxy.INSTANCE.readOnly=false;IgnoredDimensionCsvHandler.INSTANCE.ignored=false;WorldServer w=new WorldServer();w.provider.dimensionId=1;DimensionManager.current=w;return w;}
 static Chunk put(WorldServer w,int x,int z){Chunk c=new Chunk();c.worldObj=w;c.xPosition=x;c.zPosition=z;w.chunks.id2ChunkMap.put(ChunkCoordIntPair.chunkXZ2Int(x,z),c);return c;}
 static void run(){for(int n=0;n<100000&&p.status().contains("RUNNING");n++){p.tick();h.drain();}check(!p.status().contains("RUNNING"),"terminated");}
 static void pass(String name){cases++;System.out.println("PASS "+name);}
 static void reject(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){rejected=true;}check(rejected,"expected reject");}
 public static void main(String[] args){
  WorldServer w=reset();p.start(w,0,0,4);run();check(w.chunks.id2ChunkMap.reads==81&&SharedApi.submitted==0,"unloaded map checks");check(p.status().contains("unloaded=81"),"unknown != air");pass("unloaded positions never load or generate");
  w=reset();put(w,0,0);p.start(w,0,0,0);run();check(SharedApi.submitted==1&&p.status().contains("submitted=1"),"resident air submitted");check(p.status().contains("Submitted does not mean saved"),"honest progress");pass("fully populated all-air resident chunk accepted; submission not persistence");
  w=reset();put(w,-1,-1);p.start(w,-1,-1,0);run();check(SharedApi.submitted==1,"negative block floor");pass("negative coordinates preserve chunk identity");
  w=reset();Chunk c=put(w,0,0);c.isLightPopulated=false;p.start(w,0,0,0);run();check(SharedApi.submitted==0&&p.status().contains("incomplete=1"),"light incomplete");pass("incomplete lighting rejected");
  w=reset();c=put(w,0,0);c.isTerrainPopulated=false;p.start(w,0,0,0);run();check(SharedApi.submitted==0,"terrain incomplete");pass("incomplete terrain rejected");
  w=reset();c=put(w,0,0);c.biomes=new byte[2];p.start(w,0,0,0);run();check(SharedApi.submitted==0,"biome incomplete");pass("invalid biome array rejected");
  w=reset();c=put(w,0,0);c.worldObj=new WorldServer();p.start(w,0,0,0);run();check(SharedApi.submitted==0,"wrong world");pass("resident chunk from another world rejected");
  w=reset();SharedApi.busy=true;put(w,0,0);p.start(w,0,0,0);run();check(SharedApi.submitted==0&&p.status().contains("alreadyUpdating=1"),"duplicate not submitted");pass("existing update not duplicated");
  w=reset();put(w,0,0);p.start(w,0,0,0);p.tick();p.stop();h.drain();check(SharedApi.submitted==0,"cancelled pending work ran");pass("stop cancels queued capture");
  w=reset();put(w,0,0);p.start(w,0,0,0);p.tick();p.onWorldUnload(w);h.drain();check(SharedApi.submitted==0,"unloaded work ran");pass("dimension unload cancels queued capture");
  w=reset();put(w,0,0);p.start(w,0,0,0);p.tick();DimensionManager.current=new WorldServer();DimensionManager.current.provider.dimensionId=1;h.drain();p.tick();check(SharedApi.submitted==0&&p.status().contains("CANCELLED"),"new world same id");pass("same dimension id replacement rejected");
  w=reset();put(w,0,0);p.start(w,0,0,0);p.tick();SharedApi.world.level=new com.seibel.distanthorizons.core.level.IDhLevel(){};h.drain();p.tick();check(SharedApi.submitted==0,"new dh level");pass("DH level replacement rejected");
  w=reset();put(w,0,0);p.start(w,0,0,0);p.tick();h.session++;h.drain();p.tick();check(SharedApi.submitted==0,"new session");pass("server session replacement rejected");
  w=reset();put(w,0,0);p.start(w,0,0,0);p.tick();p.onServerStopping();h.drain();check(SharedApi.submitted==0,"server shutdown");pass("server shutdown cancels queued capture");
  w=reset();h.reject=true;p.start(w,0,0,0);run();check(p.status().contains("FAILED"),"queue rejection");pass("queue rejection fails finitely");
  w=reset();put(w,0,0);ChunkWrapper.fail=true;p.start(w,0,0,0);run();check(p.status().contains("FAILED")&&SharedApi.submitted==0,"capture failure");pass("capture failure stops without a false completion");
  w=reset();for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)put(w,x,z);p.start(w,0,0,2);for(int i=0;i<5;i++){p.tick();h.drain();}check(SharedApi.submitted==1,"more than one per five ticks");run();check(SharedApi.submitted==25&&h.maxPending==1,"bounded finite capture");pass("one pending task and at most one submission per five ticks");
  w=reset();final WorldServer boundWorld=w;reject(()->p.start(boundWorld,0,0,-1));reject(()->p.start(boundWorld,0,0,65));reject(()->p.start(boundWorld,Integer.MAX_VALUE,0,1));p.start(w,0,0,0);reject(()->p.start(boundWorld,0,0,0));p.stop();pass("radius/coordinate bounds and duplicate jobs rejected");
  w=reset();h.owner=false;final WorldServer ownerWorld=w;reject(()->p.start(ownerWorld,0,0,0));h.owner=true;pass("non-owner thread rejected");
  w=reset();DhApiWorldProxy.INSTANCE.readOnly=true;final WorldServer readonlyWorld=w;reject(()->p.start(readonlyWorld,0,0,0));DhApiWorldProxy.INSTANCE.readOnly=false;IgnoredDimensionCsvHandler.INSTANCE.ignored=true;reject(()->p.start(readonlyWorld,0,0,0));pass("read-only and ignored dimensions rejected");
  w=reset();java.util.List<String> messages=new java.util.ArrayList<>();PrepareLoadedCommand command=new PrepareLoadedCommand();command.execute(messages::add,new String[]{"prepare","loaded","1","0","0","0"});check(p.status().contains("RUNNING"),"reachable command");command.execute(messages::add,new String[]{"prepare","stop"});check(!p.status().contains("RUNNING"),"reachable cancellation");command.execute(messages::add,new String[]{"prepare","loaded","1","0","0","bad"});check(messages.get(messages.size()-1).contains("whole numbers"),"invalid number message");pass("actual command routes loaded/status/stop and validates numbers");
  System.out.println("CASES="+cases);
 }
}
'''
with tempfile.TemporaryDirectory(prefix='mirai-loaded-prepare-') as tmp:
    temp = pathlib.Path(tmp)
    for rel, source in fixtures.items():
        p=temp/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(source)
    for rel in ['common/util/LegacyLoadedTerrainPreparation.java','common/commands/PrepareLoadedCommand.java']:
        source=repo/'common/src/main/java/com/seibel/distanthorizons'/rel
        target=temp/'com/seibel/distanthorizons'/rel
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text('\n'.join(l for l in source.read_text().splitlines() if not l.startswith('#')))
    (temp/'LoadedPrepareProbe.java').write_text(probe)
    classes=temp/'classes';classes.mkdir()
    compile_cmd=[str(base/'toolchains/jdk21/bin/javac'),'--release','8','-d',str(classes)]+[str(p) for p in temp.rglob('*.java')]
    subprocess.run(compile_cmd,check=True)
    results={}
    for runtime in ['jdk8','jdk21']:
        proc=subprocess.run([str(base/'toolchains'/runtime/'bin/java'),'-cp',str(classes),'LoadedPrepareProbe'],text=True,capture_output=True)
        (out/(runtime+'.log')).write_text(proc.stdout+proc.stderr)
        if proc.returncode: print(proc.stdout,proc.stderr);raise SystemExit(proc.returncode)
        results[runtime]={'exit':proc.returncode,'cases':int(proc.stdout.split('CASES=')[-1].strip())}
    (out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(results))
