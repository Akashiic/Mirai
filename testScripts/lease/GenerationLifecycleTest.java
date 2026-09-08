import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.ChunkTicketPool;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises production helpers, with a fake platform ticket allocator and no Minecraft world. */
public final class GenerationLifecycleTest
{
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static final class Ticket {
        final Set<String> forced = new HashSet<>();
        boolean released;
    }
    static final class Platform {
        final List<Ticket> tickets = new ArrayList<>();
        int maxTickets = 10, capacity = 2;
        String failForce, failUnforce;
        Ticket failRelease;
        ChunkTicketPool<String, Ticket> pool() {
            return new ChunkTicketPool<>(() -> {
                int live = 0;
                for (Ticket t : tickets) if (!t.released) live++;
                if (live >= maxTickets) return null;
                Ticket t = new Ticket(); tickets.add(t); return t;
            }, t -> capacity, (t, p) -> {
                check(!t.released && t.forced.size() < capacity, "Ticket capacity exceeded");
                t.forced.add(p);
                if (p.equals(failForce)) throw new IllegalStateException("force failure after mutation");
            }, (t, p) -> {
                if (p.equals(failUnforce)) throw new IllegalStateException("unforce failure");
                t.forced.remove(p);
            }, t -> {
                if (t == failRelease) throw new IllegalStateException("release failure");
                check(!t.released, "Ticket released twice");
                t.forced.clear(); t.released = true;
            });
        }
    }
    private static void tickets() {
        Platform p = new Platform(); ChunkTicketPool<String, Ticket> pool = p.pool();
        for (int i=0;i<5;i++) pool.acquire("p"+i);
        check(p.tickets.size()==3,"Expected three capacity-bounded tickets");
        pool.releasePosition("p0"); pool.releasePosition("p1");
        check(p.tickets.get(0).released,"Empty ticket retained its quota");
        pool.releasePosition("p1");
        pool.close(); pool.close();
        for (Ticket t:p.tickets) check(t.released && t.forced.isEmpty(),"Close retained ticket");
        try { pool.acquire("closed"); throw new AssertionError("Acquired after close"); }
        catch (CancellationException expected) { checks++; }

        p=new Platform();p.capacity=1;p.maxTickets=1;pool=p.pool();pool.acquire("owned");
        try { pool.acquire("denied"); throw new AssertionError("Null ticket accepted"); }
        catch (IllegalStateException expected) { checks++; }
        check(p.tickets.get(0).forced.contains("owned"),"Denial released another position");
        pool.close();

        p=new Platform();p.capacity=0;pool=p.pool();
        try { pool.acquire("zero"); throw new AssertionError("Zero capacity accepted"); }
        catch (IllegalStateException expected) { checks++; }
        check(p.tickets.get(0).released,"Unusable ticket leaked");pool.close();

        p=new Platform();p.failForce="bad";pool=p.pool();pool.acquire("good");
        try { pool.acquire("bad"); throw new AssertionError("Force failure swallowed"); }
        catch (IllegalStateException expected) { checks++; }
        check(p.tickets.get(0).forced.equals(Collections.singleton("good")),"Partial force not rolled back");
        pool.close();

        p=new Platform();p.capacity=1;pool=p.pool();pool.acquire("a");pool.acquire("b");
        p.failRelease=p.tickets.get(0);
        try {pool.close();throw new AssertionError("Close failure swallowed");}
        catch(IllegalStateException expected){checks++;}
        check(p.tickets.get(1).released,"Close stopped before remaining ticket");
        p.failRelease=null;pool.close();check(p.tickets.get(0).released,"Failed release could not be retried");

        p=new Platform();pool=p.pool();pool.acquire("a");p.failUnforce="a";
        try{pool.releasePosition("a");throw new AssertionError("Unforce failure swallowed");}
        catch(IllegalStateException expected){checks++;}
        pool.close();check(p.tickets.get(0).released,"Close missed failed unforce ownership");
    }
    private static void tasks() throws Exception {
        ServerThreadTaskHandler h=ServerThreadTaskHandler.INSTANCE;
        AtomicInteger ran=new AtomicInteger();
        check(h.queueTask(ran::incrementAndGet).isCancelled(),"Client-only accepted a server task");
        h.reset();long old=h.getSessionId();
        CompletableFuture<Integer> stale=h.queueTask(old,ran::incrementAndGet);
        h.reset();check(stale.isCancelled(),"Reset left old task pending");
        check(h.queueTask(old,ran::incrementAndGet).isCancelled(),"Old producer entered new session");
        check(h.queueEssentialTask(old,ran::incrementAndGet).isCancelled(),"Old timer entered new session");
        CompletableFuture<Integer> cancelled=h.queueTask(ran::incrementAndGet);cancelled.cancel(false);
        h.runTasks(TimeUnit.SECONDS.toNanos(1));check(ran.get()==0,"Cancelled work executed");

        List<CompletableFuture<Integer>> accepted=new ArrayList<>();
        for(int i=0;i<4096;i++) accepted.add(h.queueTask(ran::incrementAndGet));
        CompletableFuture<Integer> rejected=h.queueTask(ran::incrementAndGet);
        check(rejected.isCompletedExceptionally(),"Queue accepted beyond capacity");
        CompletableFuture<Integer> essential=h.queueEssentialTask(() -> {check(ran.get()==0,"Cleanup ran after load");return 42;});
        h.runTasks(TimeUnit.SECONDS.toNanos(5));
        check(essential.join()==42 && ran.get()==4096,"Saturation blocked cleanup or lost work");
        for(CompletableFuture<Integer> f:accepted)check(f.isDone()&&!f.isCompletedExceptionally(),"Accepted task not completed");

        CompletableFuture<Boolean> wrongThread=new CompletableFuture<>();
        Thread t=new Thread(() -> {try {h.runTasks(1000);wrongThread.complete(false);}catch(IllegalStateException expected){wrongThread.complete(true);}});
        t.start();t.join();check(wrongThread.join(),"Other thread executed world queue");
        long epoch=h.getSessionId();
        List<CompletableFuture<Integer>> futures=Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start=new CountDownLatch(1);
        Thread producer=new Thread(() -> {try{start.await();}catch(InterruptedException e){throw new AssertionError(e);}for(int i=0;i<1000;i++)futures.add(h.queueTask(epoch,ran::incrementAndGet));});
        producer.start();start.countDown();h.cancelPendingTasks();producer.join();h.reset();
        for(CompletableFuture<Integer> f:futures)check(f.isCancelled(),"Shutdown/submit race left live work");
        check(h.queueTask(ran::incrementAndGet)!=null,"New session cannot accept");
        h.runTasks(TimeUnit.SECONDS.toNanos(1));check(ran.get()==4097,"Stale tasks crossed reset");
        h.cancelPendingTasks();check(h.queueEssentialTask(ran::incrementAndGet).isCancelled(),"Stopped server accepted cleanup");
    }
    public static void main(String[] args) throws Exception {
        tickets();tasks();
        System.out.println("Generation lifecycle PASS: "+checks+" checks; Java "+System.getProperty("java.version"));
    }
}
