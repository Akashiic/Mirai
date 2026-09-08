"""Compile the ACTUAL SharedApi transition methods with narrow cleanup doubles.

This checks failure ordering/aggregation, not Minecraft or a complete core build.
Usage: python tests/transport/test_shared_api_cleanup.py /absolute/jdk21
"""
from pathlib import Path
import hashlib
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/api/internal/SharedApi.java").read_text()
start = source.index("\tpublic static void setDhWorld(AbstractDhWorld newWorld)")
end = source.index("\n\t@Nullable\n\tpublic static AbstractDhWorld getAbstractDhWorld()", start)
actual_methods = source[start:end]
observer_source = root / "coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/logging/mirai/MiraiWorldLifecycle.java"

fixture = r'''
import java.util.*;
import com.seibel.distanthorizons.core.logging.mirai.MiraiWorldLifecycle;
import com.seibel.distanthorizons.core.logging.mirai.MiraiWorldLifecycle.Phase;

public class SharedApiLifecycleTest {
    static volatile AbstractDhWorld currentWorld;
    static final Object worldLockObject = new Object();
    static final List<String> calls = new ArrayList<>();
    static final Map<String, Throwable> failures = new HashMap<>();
    static Renderer MC_RENDER = new Renderer();
    static final ChunkManager WORLD_CHUNK_UPDATE_MANAGER = new ChunkManager();
    static int assertions;

    static void action(String name) {
        calls.add(name);
        Throwable failure = failures.get(name);
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
    }
    static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label + " / " + calls);
    }
    static void reset() { calls.clear(); failures.clear(); currentWorld = null; MC_RENDER = new Renderer(); }
    static Throwable transition(AbstractDhWorld world) {
        try { setDhWorld(world); return null; }
        catch (Throwable failure) { return failure; }
    }
    static void allCleanup() {
        for (String call : Arrays.asList("shutdown", "debug", "repos", "chunks", "stats", "textures", "unload", "readonly")) {
            check(calls.contains(call), "cleanup attempted: " + call);
        }
        check(calls.indexOf("readonly") > calls.indexOf("unload"), "read-only reset follows unload even when listener fails");
    }

    public static void main(String[] args) {
        reset();
        AbstractDhWorld old = new AbstractDhWorld("old", true);
        AbstractDhWorld next = new AbstractDhWorld("new", true);
        currentWorld = old;
        check(transition(next) == null, "normal replacement succeeds");
        check(currentWorld == next && calls.equals(Arrays.asList("old.close", "setup", "load")), "normal event sequence preserved");

        reset();
        old = new AbstractDhWorld("old", true);
        next = new AbstractDhWorld("candidate", false);
        currentWorld = old;
        RuntimeException original = new RuntimeException("old close");
        failures.put("old.close", original);
        failures.put("candidate.close", new RuntimeException("candidate close"));
        failures.put("shutdown", new RuntimeException("shutdown"));
        failures.put("debug", new RuntimeException("debug"));
        failures.put("unload", new RuntimeException("unload"));
        check(transition(next) == original, "original exception preserved");
        check(currentWorld == null, "old failure detaches world");
        check(!calls.contains("setup") && !calls.contains("load"), "failed cleanup never publishes replacement");
        check(calls.indexOf("candidate.close") < calls.indexOf("shutdown"), "unpublished candidate disposed first");
        check(original.getSuppressed().length == 4, "all additional failures aggregated");
        allCleanup();

        reset();
        next = new AbstractDhWorld("new", true);
        RuntimeException setup = new RuntimeException("setup");
        failures.put("setup", setup);
        check(transition(next) == setup && currentWorld == null, "failed setup detached and reported");
        check(calls.contains("new.close") && !calls.contains("load"), "failed setup closes candidate with its context");
        allCleanup();

        reset();
        next = new AbstractDhWorld("new", true);
        failures.put("load", new RuntimeException("load listener"));
        check(transition(next) == failures.get("load") && currentWorld == null, "load listener error rolls back publication");
        check(calls.indexOf("new.close") > calls.indexOf("load"), "published failed candidate closed");
        allCleanup();

        reset();
        old = new AbstractDhWorld("old", true);
        currentWorld = old;
        MC_RENDER = null;
        check(transition(null) == null && currentWorld == null, "dedicated unload succeeds without render wrapper");
        check(!calls.contains("framebuffer"), "dedicated path never dereferences client renderer");
        allCleanup();

        reset();
        old = new AbstractDhWorld("old", true);
        currentWorld = old;
        Error fatal = new AssertionError("close failure");
        failures.put("old.close", fatal);
        failures.put("repos", fatal);
        check(transition(null) == fatal && currentWorld == null, "Error identity preserved after cleanup");
        check(fatal.getSuppressed().length == 0, "no self-suppression exception interrupts cleanup");
        allCleanup();

        reset();
        old = new AbstractDhWorld("same", true);
        currentWorld = old;
        check(transition(old) == null && currentWorld == old && calls.isEmpty(), "same world is not closed and republished");
        System.out.println("PASS SharedApiLifecycleTest: " + assertions + " assertions; actual transition source, cleanup doubles");
    }

    static class AbstractDhWorld {
        final String name;
        final boolean expectsPublished;
        AbstractDhWorld(String name, boolean expectsPublished) { this.name = name; this.expectsPublished = expectsPublished; }
        void close() {
            check(expectsPublished ? currentWorld == this : currentWorld == null, "close sees proper owner context " + name);
            action(name + ".close");
        }
    }
    static class ThreadPoolUtil { static void setupThreadPools() { action("setup"); } static void shutdownThreadPools() { action("shutdown"); } }
    static class Renderer { void clearTargetFrameBuffer() { action("framebuffer"); } }
    static class AbstractDebugWireframeRenderer { void clearRenderables() { action("debug"); } }
    static class SingletonInjector { static final SingletonInjector INSTANCE = new SingletonInjector(); <T> T get(Class<T> kind) { return kind.cast(new AbstractDebugWireframeRenderer()); } }
    static class AbstractDhRepo { static void closeAllConnections() { action("repos"); } }
    static class ChunkManager { void clear() { action("chunks"); } }
    static class RenderThreadTaskHandler { static final RenderThreadTaskHandler INSTANCE = new RenderThreadTaskHandler(); void clearDebugStats() { action("stats"); } }
    static class BlockTextureRegistry { static final BlockTextureRegistry INSTANCE = new BlockTextureRegistry(); void clear() { action("textures"); } }
    static class DhApiWorldLoadEvent { static class EventParam {} }
    static class DhApiWorldUnloadEvent { static class EventParam {} }
    static class ApiEventInjector { static final ApiEventInjector INSTANCE = new ApiEventInjector(); void fireAllEvents(Class<?> event, Object param) { action(event == DhApiWorldLoadEvent.class ? "load" : "unload"); } }
    static class DhApiWorldProxy { static final DhApiWorldProxy INSTANCE = new DhApiWorldProxy(); void setReadOnly(boolean value, boolean force) { action("readonly"); } }
'''

with tempfile.TemporaryDirectory(prefix="mirai-world-cleanup-") as directory:
    work = Path(directory)
    test_source = work / "SharedApiLifecycleTest.java"
    test_source.write_text(fixture + actual_methods + "\n}\n")
    jdk = Path(sys.argv[1])
    version = subprocess.run([str(jdk / "bin/javac"), "-version"], capture_output=True, text=True, check=True)
    flags = ["-source", "8", "-target", "8"] if "1.8." in version.stdout + version.stderr else ["--release", "8"]
    observer = work / "MiraiWorldLifecycle.java"
    observer.write_bytes(observer_source.read_bytes())
    diagnostics = work / "MiraiDiagnostics.java"
    diagnostics.write_text('''package com.seibel.distanthorizons.core.logging.mirai;
public final class MiraiDiagnostics {
    public static boolean enabled() { return true; }
    public static void event(String category, String detail) { }
}''')
    subprocess.run([str(jdk / "bin/javac"), *flags, "-d", str(work), str(test_source), str(observer), str(diagnostics)], check=True)
    subprocess.run([str(jdk / "bin/java"), "-cp", str(work), "SharedApiLifecycleTest"], check=True)
    print("SharedApi sha256=" + hashlib.sha256(source.encode()).hexdigest())
    print("MiraiWorldLifecycle sha256=" + hashlib.sha256(observer_source.read_bytes()).hexdigest())

pool_source = (root / "coreSubProjects/core/src/main/java/com/seibel/distanthorizons/core/util/threading/ThreadPoolUtil.java").read_text()
start = pool_source.index("\tpublic static void shutdownThreadPools()")
end = pool_source.index("\n\t///endregion", start)
actual_shutdown = pool_source[start:end]
pool_fixture = r'''
import java.util.*;
public class PoolShutdownTest {
    static volatile ThreadPoolExecutor networkClientHandlerThreadPool;
    static PriorityTaskPicker taskPicker;
    static ThreadPoolExecutor beaconCullingThreadPool;
    static ThreadPoolExecutor fullDataMigrationThreadPool;
    static final List<String> calls = new ArrayList<>();
    static final Map<String, RuntimeException> failures = new HashMap<>();
    static int assertions;
    static void check(boolean value, String reason) {
        assertions++;
        if (!value) throw new AssertionError(reason + " / " + calls);
    }
    static void action(String step) {
        calls.add(step);
        if (failures.containsKey(step)) throw failures.get(step);
    }
    static class ThreadPoolExecutor {
        final String name;
        ThreadPoolExecutor(String name) { this.name = name; }
        List<Runnable> shutdownNow() {
            check(networkClientHandlerThreadPool == null, "network admission disabled before shutdown");
            action(name);
            return Collections.emptyList();
        }
        void shutdown() { action(name); }
    }
    static class PriorityTaskPicker { void shutdownNow() { action("picker"); } }
    static class ClientApi { static void discardPluginMessageTasks(List<Runnable> tasks) { action("discard"); } }
    static void reset(boolean create) {
        calls.clear(); failures.clear();
        networkClientHandlerThreadPool = create ? new ThreadPoolExecutor("network") : null;
        taskPicker = create ? new PriorityTaskPicker() : null;
        beaconCullingThreadPool = create ? new ThreadPoolExecutor("beacon") : null;
        fullDataMigrationThreadPool = create ? new ThreadPoolExecutor("migration") : null;
    }
    public static void main(String[] args) {
        reset(false);
        shutdownThreadPools();
        check(calls.isEmpty(), "partial setup with no pools is harmless");
        fullDataMigrationThreadPool = new ThreadPoolExecutor("migration");
        shutdownThreadPools();
        check(calls.equals(Arrays.asList("migration")), "null earlier pools never skip later pool");

        reset(true);
        shutdownThreadPools();
        check(calls.equals(Arrays.asList("network", "discard", "picker", "beacon", "migration")), "all independent stops preserve order");
        check(networkClientHandlerThreadPool == null, "network pool remains unpublished");

        reset(true);
        for (String key : Arrays.asList("network", "picker", "beacon", "migration")) {
            failures.put(key, new RuntimeException(key));
        }
        Throwable caught = null;
        try { shutdownThreadPools(); } catch (Throwable failure) { caught = failure; }
        check(caught == failures.get("network"), "initial stop exception preserved");
        check(caught.getSuppressed().length == 3, "all later pool failures aggregated");
        check(calls.equals(Arrays.asList("network", "picker", "beacon", "migration")), "a pool failure never aborts other stops");

        reset(true);
        failures.put("discard", new RuntimeException("discard"));
        caught = null;
        try { shutdownThreadPools(); } catch (Throwable failure) { caught = failure; }
        check(caught == failures.get("discard") && calls.contains("migration"), "discard failure also permits remaining stops");
        System.out.println("PASS PoolShutdownTest: " + assertions + " assertions; actual shutdown source, pool doubles");
    }
'''
with tempfile.TemporaryDirectory(prefix="mirai-pool-cleanup-") as directory:
    work = Path(directory)
    test_source = work / "PoolShutdownTest.java"
    test_source.write_text(pool_fixture + actual_shutdown + "\n}\n")
    subprocess.run([str(jdk / "bin/javac"), *flags, "-d", str(work), str(test_source)], check=True)
    subprocess.run([str(jdk / "bin/java"), "-cp", str(work), "PoolShutdownTest"], check=True)
