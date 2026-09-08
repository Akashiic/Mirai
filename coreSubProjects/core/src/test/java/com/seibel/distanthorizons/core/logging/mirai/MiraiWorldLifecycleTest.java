package com.seibel.distanthorizons.core.logging.mirai;

import org.junit.After;
import org.junit.Test;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MiraiWorldLifecycleTest
{
    private MiraiLogSession session;
    private Path directory;

    private void install(int capacity) throws Exception
    {
        directory = Files.createTempDirectory("mirai-lifecycle-test-");
        session = new MiraiLogSession(directory, "TEST", capacity, 65536, 4);
        Field field = MiraiDiagnostics.class.getDeclaredField("session");
        field.setAccessible(true);
        field.set(null, session);
    }
    private List<String> events() throws Exception
    {
        session.start();
        session.close();
        session.awaitTermination(5000);
        List<String> events = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve("latest.log"), StandardCharsets.UTF_8))
        { if (line.contains("\"kind\":\"world_lifecycle\"")) { events.add(line); } }
        return events;
    }
    private String id(String line) { return line.split("id=", 2)[1].split(" ", 2)[0]; }
    @After public void cleanup() throws Exception
    {
        if (session != null) { session.close(); session.awaitTermination(5000); }
        Field field = MiraiDiagnostics.class.getDeclaredField("session");
        field.setAccessible(true); field.set(null, null);
        if (directory != null)
        {
            try (java.util.stream.Stream<Path> files = Files.walk(directory))
            { for (Path path : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) { Files.deleteIfExists(path); } }
        }
    }
    @Test public void nestedPhasesKeepDistinctPairsAndCallerThread() throws Exception
    {
        install(16);
        Thread caller = Thread.currentThread();
        AtomicInteger calls = new AtomicInteger();
        MiraiWorldLifecycle.run(MiraiWorldLifecycle.Phase.OLD_WORLD_CLOSE, () ->
            MiraiWorldLifecycle.run(MiraiWorldLifecycle.Phase.THREAD_POOLS_SHUTDOWN, () ->
            { assertSame(caller, Thread.currentThread()); calls.incrementAndGet(); }));
        assertEquals(1, calls.get());
        List<String> records = events();
        assertEquals(4, records.size());
        assertEquals(id(records.get(0)), id(records.get(3)));
        assertEquals(id(records.get(1)), id(records.get(2)));
        assertNotEquals(id(records.get(0)), id(records.get(1)));
        assertTrue(records.get(0).contains("state=begin"));
        assertTrue(records.get(3).contains("state=end completed=true wall_ns="));
    }
    @Test public void errorIdentityAndFailureEndArePreserved() throws Exception
    {
        install(8);
        Error original = new AssertionError("private world detail");
        try { MiraiWorldLifecycle.run(MiraiWorldLifecycle.Phase.DATABASE_CLOSE, () -> { throw original; }); fail(); }
        catch (Error actual) { assertSame(original, actual); }
        List<String> records = events();
        assertEquals(2, records.size());
        assertTrue(records.get(1).contains("completed=false"));
        assertFalse(records.toString().contains("private world detail"));
    }
    @Test public void fullDiagnosticQueueNeverSkipsTheAction() throws Exception
    {
        install(1);
        AtomicInteger calls = new AtomicInteger();
        MiraiWorldLifecycle.run(MiraiWorldLifecycle.Phase.EXPLICIT_GC, calls::incrementAndGet);
        assertEquals(1, calls.get());
        assertEquals(1, session.queued());
        assertEquals(1, session.dropped.get());
    }
    @Test public void disabledObserverPreservesRuntimeException() throws Exception
    {
        Field field = MiraiDiagnostics.class.getDeclaredField("session");
        field.setAccessible(true); field.set(null, null);
        RuntimeException original = new RuntimeException();
        try { MiraiWorldLifecycle.run(MiraiWorldLifecycle.Phase.WORLD_LOAD_EVENT, () -> { throw original; }); fail(); }
        catch (RuntimeException actual) { assertSame(original, actual); }
        assertFalse(MiraiDiagnostics.enabled());
    }
}
