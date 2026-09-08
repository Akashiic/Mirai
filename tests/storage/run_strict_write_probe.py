#!/usr/bin/env python3
"""Exercise the exact updater method with deterministic persistence boundaries."""
import hashlib
import json
import subprocess
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
workspace = repo.parent
out = workspace / 'homologation-0.1.4-evidence/storage/strict-write'
out.mkdir(parents=True, exist_ok=True)
source = repo / 'coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/V2/FullDataUpdaterV2.java'
text = source.read_text()
def extract(signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]
method = '\n'.join(extract(signature) for signature in [
    '\tpublic void updateDataSource(@NotNull FullDataSourceV2 inputData)',
    '\tprivate void notifyCommittedUpdate(FullDataSourceV2 dataSource)',
    '\tprivate void beginWrite()', '\tprivate void endWrite()', '\tprivate void tryCompleteClose()'])
fixture = r'''
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
public class StrictWriteProbe {
 @interface NotNull {}
 static class FullDataSourceV2 implements AutoCloseable {
  boolean modified; int closes; long getPos(){return 6;}
  boolean updateFromDataSource(FullDataSourceV2 other){return other.modified;}
  public void close(){closes++;}
 }
 static class FullDataSourceV2DTO implements AutoCloseable { int closes; public void close(){closes++;} }
 static class Repo { int checked, ordinary; boolean acknowledge=true;
  boolean saveChecked(FullDataSourceV2DTO dto){checked++;return acknowledge;}
  void save(FullDataSourceV2DTO dto){ordinary++;}
 }
 static class Level { boolean hasSkyLight(){return true;} }
 static class Provider { final Repo repo=new Repo(); final Level levelWrapper=new Level();
  FullDataSourceV2 recipient=new FullDataSourceV2(); FullDataSourceV2 get(long pos){return recipient;}
 }
 static class PositionalLockProvider { final ReentrantLock lock=new ReentrantLock(); ReentrantLock getLock(long p){return lock;} }
 static class DhSectionPos { static final byte SECTION_BLOCK_DETAIL_LEVEL=6; static byte getDetailLevel(long p){return 6;} static String toString(long p){return "position";} }
 static class LodUtil { static final int MAX_MC_LIGHT=15, MIN_MC_LIGHT=0; }
 static class DhLightingEngine { static final DhLightingEngine INSTANCE=new DhLightingEngine(); int bakes;
  void bakeDataSourceSkyLight(FullDataSourceV2 d,int light){bakes++;}
 }
 interface IDataSourceUpdateListenerFunc<T> { void OnDataSourceUpdated(T data); }
 static class Log { int errors; void error(String s,Throwable e){errors++;} }
 static final Log LOGGER=new Log();
 final AtomicBoolean isShutdownRef=new AtomicBoolean();
 final Object lifecycleLock=new Object(); int activeWrites;
 final Set<Object> pendingUpdates=new HashSet<>();
 final CompletableFuture<Void> closedFuture=new CompletableFuture<>();
 final PositionalLockProvider updateLockProvider=new PositionalLockProvider();
 final Set<Long> lockedPosSet=new HashSet<>();
 final ReentrantReadWriteLock updateListenerReadWriteLock=new ReentrantReadWriteLock();
 final ArrayList<IDataSourceUpdateListenerFunc<FullDataSourceV2>> dateSourceUpdateListeners=new ArrayList<>();
 final Provider provider=new Provider();
 FullDataSourceV2DTO dto=new FullDataSourceV2DTO();
 FullDataSourceV2DTO createDtoFromDataSource(FullDataSourceV2 d){return dto;}
 // METHOD
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void rejects(Runnable r){boolean failed=false;try{r.run();}catch(IllegalStateException e){failed=true;}check(failed,"unconfirmed write acknowledged");}
 static void released(StrictWriteProbe p){check(!p.updateLockProvider.lock.isLocked()&&p.lockedPosSet.isEmpty(),"lock leaked");}
 public static void main(String[] args){
  FullDataSourceV2 input=new FullDataSourceV2();
  StrictWriteProbe p=new StrictWriteProbe();
  p.dateSourceUpdateListeners.add(d->{throw new AssertionError("unchanged source notified listeners");});
  p.updateDataSource(input);
  check(p.provider.repo.checked==1&&p.provider.repo.ordinary==0,"unchanged migration skipped acknowledged write");
  check(p.provider.recipient.closes==1&&p.dto.closes==1,"temporary resources leaked");
  check(DhLightingEngine.INSTANCE.bakes==0,"unchanged migration rebaked lighting");released(p);
  p=new StrictWriteProbe();p.updateDataSource(input);
  check(p.provider.repo.checked==1&&p.provider.repo.ordinary==0,"ordinary unchanged merge lacked acknowledgement");released(p);
  StrictWriteProbe failed=new StrictWriteProbe();failed.provider.repo.acknowledge=false;
  rejects(()->failed.updateDataSource(input));released(failed);
  check(failed.provider.recipient.closes==1&&failed.dto.closes==1,"rejected write leaked resources");
  StrictWriteProbe unencodable=new StrictWriteProbe();unencodable.dto=null;
  rejects(()->unencodable.updateDataSource(input));released(unencodable);
  StrictWriteProbe absent=new StrictWriteProbe();absent.provider.recipient=null;
  rejects(()->absent.updateDataSource(input));released(absent);
  StrictWriteProbe closed=new StrictWriteProbe();closed.isShutdownRef.set(true);
  rejects(()->closed.updateDataSource(input));released(closed);
  p=new StrictWriteProbe();input.modified=true;final AtomicInteger events=new AtomicInteger();
  p.dateSourceUpdateListeners.add(d->events.incrementAndGet());p.updateDataSource(input);
  check(p.provider.repo.checked==1&&events.get()==1&&DhLightingEngine.INSTANCE.bakes==1,"modified migration path changed");released(p);
  StrictWriteProbe observers=new StrictWriteProbe();AtomicInteger observed=new AtomicInteger();
  observers.dateSourceUpdateListeners.add(d->{throw new IllegalStateException("injected observer");});
  observers.dateSourceUpdateListeners.add(d->observed.incrementAndGet());observers.updateDataSource(input);
  check(observers.provider.repo.checked==1&&observed.get()==1,"postcommit listener invalidated persistence or skipped next observer");released(observers);
  check(observers.activeWrites==0,"write lifecycle retained active count");
  System.out.println("Strict migration and normal write: "+checks+" checks passed");
 }
}
'''.replace('// METHOD', method)
java_file = out / 'StrictWriteProbe.java'
java_file.write_text(fixture)
jdk = workspace / 'toolchains'
compile = subprocess.run([str(jdk/'jdk21/bin/javac'),'--release','8','-d',str(out),str(java_file)],capture_output=True,text=True)
(out/'compile.log').write_text(compile.stdout+compile.stderr)
compile.check_returncode()
results = {'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(), 'method_sha256':hashlib.sha256(method.encode()).hexdigest(), 'boundary':'Actual method; deterministic fake provider/DTO. SQLite acknowledgements tested separately in JUnit.', 'runs':[]}
for version in ['jdk8','jdk21']:
    result = subprocess.run([str(jdk/version/'bin/java'),'-ea','-cp',str(out),'StrictWriteProbe'],capture_output=True,text=True,timeout=15)
    (out/(version+'.log')).write_text(result.stdout+result.stderr)
    results['runs'].append({'java':version,'exit':result.returncode,'output':result.stdout})
    result.check_returncode()
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
