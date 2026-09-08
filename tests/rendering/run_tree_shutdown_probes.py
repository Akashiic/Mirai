#!/usr/bin/env python3
"""Compile the actual LodQuadTree lifecycle fields, close and tryTick methods with strict doubles.

Exercises scheduling/error/lock boundaries without Minecraft or native GL. The complete
section and container classes are covered by run_render_load_lifecycle_probes.py.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--out', required=True, type=Path)
parser.add_argument('--javac', required=True)
parser.add_argument('--java', required=True)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)
source = SOURCE.read_text(encoding='utf-8')


def method(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


fields_start = source.index('private final ReentrantLock treeTickLock')
fields_end = source.index('private final AtomicBoolean requeueAllRetrievalTasksRef', fields_start)
fields = source[fields_start:fields_end]
extracted = fields + method('public void close()') + '\n' + method('public void tryTick(DhBlockPos2D playerPos)')

fixture = r'''
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

class LodQuadTree implements AutoCloseable {
__EXTRACTED__
 final ArrayList<QuadNode<LodRenderSection>> nodes = new ArrayList<>();
 Level level = new Level();
 int ticks, details;
 boolean failDetail;
 static final Logger LOGGER = new Logger();
 static final Debug DEBUG_RENDERER = new Debug();
 Iterator<QuadNode<LodRenderSection>> nodeIterator(){return nodes.iterator();}
 void updateDetailLevelVariables(){details++;if(failDetail)throw new IllegalStateException("detail");}
 void updateAllRenderSections(DhBlockPos2D pos){ticks++;}
 boolean lockHeld(){return treeTickLock.isHeldByCurrentThread();}
 void lock(){treeTickLock.lock();} void unlock(){treeTickLock.unlock();}
}
class DhBlockPos2D {}
class Level {Level getLevelWrapper(){return this;}String getDhIdentifier(){return "fixture";}}
class Logger {void error(String text, Throwable error){Driver.tickErrors++;}}
class QuadNode<T> {T value;QuadNode(T value){this.value=value;}}
class LodRenderSection {
 int closes; RuntimeException failure;
 void close(){closes++;Driver.check(Driver.tree.isClosing(),"section_closed_after_tree_guard");if(failure!=null)throw failure;}
}
class Debug {void unregister(Object a,Object b){Driver.preparation("debug");}}
class Listener {final String name;Listener(String name){this.name=name;}void removeListener(Object o){Driver.preparation(name);}}
class Config {
 static class Client {static class Advanced {static class Debugging {static class DebugWireframe {static final Object showQuadTreeRenderStatus=new Object();}}}}
 static class Common {static class WorldGenerator {static final Listener enableDistantGeneration=new Listener("worldgen");}}
 static class Server {static final Listener enableServerGeneration=new Listener("server");}
}
class ThreadPoolUtil {static ThreadPoolExecutor executor;static ThreadPoolExecutor getCleanupExecutor(){return executor;}}
class Executor extends ThreadPoolExecutor {
 final ArrayDeque<Runnable> queued = new ArrayDeque<>(); boolean reject; int submitted;
 Executor(){super(1,1,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<Runnable>());}
 public void execute(Runnable task){submitted++;if(reject)throw new RejectedExecutionException("fixture");queued.add(task);}
 void drain(){Runnable task;while((task=queued.poll())!=null)task.run();}
}
public class Driver {
 static LodQuadTree tree; static int tickErrors; static String failPreparation;
 static final RuntimeException preparationError=new IllegalArgumentException("preparation");
 static final ArrayList<String> preparations=new ArrayList<>();
 static int callbacks;
 static void check(boolean condition,String label){if(!condition)throw new AssertionError("ASSERT "+label);}
 static void preparation(String name){preparations.add(name);if(name.equals(failPreparation))throw preparationError;}
 static void cleaned(LodRenderSection a,LodRenderSection b){
  check(tree.getShutdownFuture().isDone(),"shutdown_signalled");check(callbacks==1,"shutdown_callback_once");
  check(a.closes==1&&b.closes==1,"all_sections_closed_once");for(QuadNode<LodRenderSection> node:tree.nodes)check(node.value==null,"closed_nodes_cleared");
 }
 public static void main(String[] args)throws Exception {
  String scenario=args[0];tree=new LodQuadTree();Executor executor=new Executor();ThreadPoolUtil.executor=executor;
  LodRenderSection a=new LodRenderSection(),b=new LodRenderSection();tree.nodes.add(new QuadNode<>(a));tree.nodes.add(new QuadNode<>(b));
  tree.getShutdownFuture().thenRun(()->{check(!tree.lockHeld(),"shutdown_callback_outside_tree_lock");callbacks++;});
  if(scenario.equals("tick-guard-and-lock")){
   tree.tryTick(new DhBlockPos2D());check(tree.ticks==1&&tree.details==1,"normal_tick_unchanged");
   tree.failDetail=true;tree.tryTick(new DhBlockPos2D());check(!tree.lockHeld()&&tickErrors==1,"failed_detail_unlocks");
   tree.close();tree.tryTick(new DhBlockPos2D());check(tree.ticks==1&&tree.details==2,"closing_tree_rejects_tick");executor.drain();cleaned(a,b);
  }else if(scenario.equals("rejected-executor")||scenario.equals("missing-executor")){
   executor.reject=true;if(scenario.equals("missing-executor"))ThreadPoolUtil.executor=null;
   tree.close();cleaned(a,b);
  }else if(scenario.equals("section-close-failure")){
   a.failure=new IllegalStateException("a");b.failure=new IllegalStateException("b");tree.close();
   try{executor.drain();throw new AssertionError("ASSERT expected_section_failure");}catch(IllegalStateException expected){check(expected==a.failure,"first_section_error_preserved");check(expected.getSuppressed().length==1&&expected.getSuppressed()[0]==b.failure,"second_section_error_suppressed");}
   cleaned(a,b);
  }else if(scenario.startsWith("preparation-failure-")){
   failPreparation=scenario.substring("preparation-failure-".length());
   if(failPreparation.equals("combined")){failPreparation="debug";executor.reject=true;a.failure=new IllegalStateException("section");}
   try{tree.close();throw new AssertionError("ASSERT expected_preparation_failure");}catch(IllegalArgumentException expected){check(expected==preparationError,"original_preparation_error_preserved");}
   if(executor.reject)check(preparationError.getSuppressed().length==1&&preparationError.getSuppressed()[0]==a.failure,"cleanup_error_does_not_mask_preparation_failure");
   else {check(!tree.getShutdownFuture().isDone(),"preparation_failure_still_schedules_async_cleanup");executor.drain();}
   cleaned(a,b);tree.close();cleaned(a,b);
  }else if(scenario.equals("tree-lock-order")){
   CountDownLatch entered=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
   tree.lock();tree.close();Runnable cleanup=executor.queued.poll();
   Thread worker=new Thread(()->{entered.countDown();try{cleanup.run();}catch(Throwable error){failure.set(error);}},"cleanup-fixture");worker.setDaemon(true);worker.start();
   check(entered.await(2,TimeUnit.SECONDS),"cleanup_started");check(!tree.getShutdownFuture().isDone()&&a.closes==0,"cleanup_waits_for_tree_lock");
   tree.unlock();worker.join(2000);check(!worker.isAlive()&&failure.get()==null,"cleanup_progress_after_tree_unlock");cleaned(a,b);
  }else {
   tree.close();check(tree.isClosing(),"closing_guard_immediate");check(!tree.getShutdownFuture().isDone()&&a.closes==0,"shutdown_waits_for_cleanup");
   check(preparations.equals(Arrays.asList("debug","worldgen","server")),"preparation_order_unchanged");
   tree.close();check(executor.submitted==1&&preparations.size()==3,"close_idempotent_while_pending");executor.drain();cleaned(a,b);tree.close();cleaned(a,b);
  }
  System.out.println("PASS "+scenario);
 }
}
'''.replace('__EXTRACTED__', extracted)
java_source = args.out / 'Driver.java'
java_source.write_text(fixture, encoding='utf-8')
classes = args.out / 'classes'
classes.mkdir(exist_ok=True)
compiled = subprocess.run([args.javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-d', str(classes), str(java_source)], capture_output=True, text=True)
(args.out / 'compile.log').write_text(compiled.stdout + compiled.stderr, encoding='utf-8')
if compiled.returncode:
    raise SystemExit(compiled.stderr)
scenarios = ['normal-close', 'tick-guard-and-lock', 'rejected-executor', 'missing-executor', 'section-close-failure',
             'preparation-failure-debug', 'preparation-failure-worldgen', 'preparation-failure-server',
             'preparation-failure-combined', 'tree-lock-order']
report = {'scope': __doc__, 'source': str(SOURCE), 'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
          'extracted_sha256': hashlib.sha256(extracted.encode()).hexdigest(), 'runs': []}
for scenario in scenarios:
    result = subprocess.run([args.java, '-Xverify:all', '-cp', str(classes), 'Driver', scenario], capture_output=True, text=True, timeout=10)
    output = result.stdout + result.stderr
    (args.out / (scenario + '.log')).write_text(output, encoding='utf-8')
    passed = result.returncode == 0 and ('PASS ' + scenario) in output and 'ASSERT ' not in output
    report['runs'].append({'scenario': scenario, 'exit_code': result.returncode, 'passed': passed})
    print(('PASS ' if passed else 'FAIL ') + scenario, flush=True)
report['passed'] = all(run['passed'] for run in report['runs'])
(args.out / 'results.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
raise SystemExit(0 if report['passed'] else 1)
