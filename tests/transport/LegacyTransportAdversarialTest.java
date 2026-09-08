import com.seibel.distanthorizons.core.network.NetworkIngressBudget;
import com.seibel.distanthorizons.forge17.network.BoundedPacketQueue;
import com.seibel.distanthorizons.forge17.network.LegacyPacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone executable against real Netty, no Forge/Minecraft test doubles. */
public final class LegacyTransportAdversarialTest
{
    private static int assertions;

    public static void main(String[] args) throws Exception
    {
        frameCopyAndOwnership();
        rejectBeforeConstruction();
        decoderFailureAndTrailingBytes();
        boundedTransactionalEncode();
        rawQueueLimitsAndLifecycle();
        budgetLimitsAndConcurrency();
        deterministicFrameFuzz();
        System.out.println("PASS LegacyTransportAdversarialTest: " + assertions + " assertions; real Netty "
                + ByteBuf.class.getProtectionDomain().getCodeSource().getLocation());
    }

    private static void frameCopyAndOwnership()
    {
        ByteBuf source = Unpooled.buffer();
        try
        {
            source.writeByte(99).writeByte(1).writeByte(2);
            source.readByte();
            byte[] copy = LegacyPacketCodec.copyFrame(source);
            check(Arrays.equals(copy, new byte[] {1, 2}), "copy respects reader index");
            check(source.refCnt() == 1 && !source.isReadable(), "borrowed buffer consumed but not released");
            source.setByte(1, 7);
            check(copy[0] == 1, "raw ingress is independent from recycled network storage");
        }
        finally { source.release(); }

        for (int size : new int[] {0, LegacyPacketCodec.MAX_FRAME_BYTES, LegacyPacketCodec.MAX_FRAME_BYTES + 1})
        {
            ByteBuf in = Unpooled.buffer(size, size).writeZero(size);
            try
            {
                byte[] copy = LegacyPacketCodec.copyFrame(in);
                check((copy != null) == (size == LegacyPacketCodec.MAX_FRAME_BYTES), "frame allocation boundary");
                check(!in.isReadable() && in.refCnt() == 1, "invalid frame drain ownership");
            }
            finally { in.release(); }
        }
        check(LegacyPacketCodec.MAX_FRAME_BYTES + 1 == 32767, "Forge discriminator fits signed-short limit");
    }

    private static void rejectBeforeConstruction()
    {
        AtomicInteger constructorCalls = new AtomicInteger();
        AtomicInteger decodeCalls = new AtomicInteger();
        for (int id = 0; id < 256; id++)
        {
            if (id == 3) { continue; }
            byte[] attack = new byte[] {(byte) id, 127, -1, -1, -1};
            fails(() -> LegacyPacketCodec.decode(attack, 4096, candidate -> candidate == 3,
                    candidate -> { constructorCalls.incrementAndGet(); return new Object(); },
                    (message, in) -> decodeCalls.incrementAndGet(), message -> { }),
                    "wrong-side/unregistered ID rejected before DTO");
        }
        fails(() -> LegacyPacketCodec.decode(new byte[4097], 4096, candidate -> true,
                candidate -> { constructorCalls.incrementAndGet(); return new Object(); },
                (message, in) -> decodeCalls.incrementAndGet(), message -> { }), "oversized request before DTO");
        check(constructorCalls.get() == 0 && decodeCalls.get() == 0, "no constructor/decoder reached by rejected headers");
    }

    private static void decoderFailureAndTrailingBytes()
    {
        AtomicReference<ByteBuf> owned = new AtomicReference<>();
        AtomicInteger cleaned = new AtomicInteger();
        for (byte[] frame : new byte[][] {{3}, {3, 0, 1, 42, 99}})
        {
            fails(() -> LegacyPacketCodec.decode(frame, 4096, id -> id == 3,
                    id -> { ByteBuf allocated = Unpooled.buffer(1); owned.set(allocated); return allocated; },
                    (message, in) -> { int size = in.readUnsignedShort(); in.skipBytes(size); },
                    message -> { cleaned.incrementAndGet(); message.release(); }), "malformed body/trailing rejected");
            check(owned.get().refCnt() == 0, "partially decoded owned resource released");
        }
        check(cleaned.get() == 2, "cleanup exactly once for each failed decode");
        String decoded = LegacyPacketCodec.decode(new byte[] {3, 42}, 4096, id -> id == 3,
                id -> "legacy", (message, in) -> check(in.readUnsignedByte() == 42, "legacy body preserved"),
                message -> { throw new AssertionError("successful decode must transfer ownership"); });
        check("legacy".equals(decoded), "legacy format round trip");
    }

    private static void boundedTransactionalEncode()
    {
        ByteBuf output = Unpooled.buffer();
        AtomicReference<ByteBuf> temp = new AtomicReference<>();
        try
        {
            output.writeByte(77);
            fails(() -> LegacyPacketCodec.encode(output, "attack", 3, 16, (message, out) ->
            {
                temp.set(out);
                out.writeZero(16);
            }), "encoder cannot grow beyond frame bound");
            check(output.writerIndex() == 1 && output.getByte(0) == 77, "failed encode emits no partial frame");
            check(temp.get().refCnt() == 0, "temporary encode buffer released on failure");
            LegacyPacketCodec.encode(output, "ok", 3, 16, (message, out) ->
            {
                temp.set(out);
                out.writeZero(15);
            });
            check(output.writerIndex() == 17 && output.getByte(1) == 3, "exact cap encodes legacy ID and body");
            check(temp.get().refCnt() == 0, "temporary encode buffer released on success");
        }
        finally { output.release(); }
    }

    private static void rawQueueLimitsAndLifecycle()
    {
        BoundedPacketQueue<Object> queue = new BoundedPacketQueue<>();
        Object peer = new Object();
        byte[] frame = {3};
        for (int i = 0; i < 64; i++)
        {
            check(queue.offer(peer, frame, null, 0), "bounded peer admits initial burst");
        }
        check(!queue.offer(peer, frame, null, 0), "peer overflow rejected");
        check(queue.size() == 1 && queue.poll(0).rejection != null, "overflow purges backlog and leaves one close event");
        for (int i = 0; i < 1000; i++)
        {
            check(!queue.offer(peer, frame, null, 0), "bad peer suppressed without log/event flood");
        }
        check(queue.size() == 0, "rejections never grow queue");
        queue.disconnect(peer);
        check(queue.offer(peer, frame, null, 0), "disconnect clears ingress identity");
        check(queue.poll(BoundedPacketQueue.MAX_AGE_NANOS).rejection != null, "stale packet closes DH without late world access");
        queue.clear();

        List<Object> peers = new ArrayList<>();
        for (int p = 0; p < 8; p++)
        {
            Object client = new Object(); peers.add(client);
            for (int i = 0; i < 64; i++)
            {
                check(queue.offer(client, frame, null, 0), "independent peers admitted");
            }
        }
        check(queue.size() == 512, "global queue capacity exact");
        Object extra = new Object();
        check(!queue.offer(extra, frame, null, 0) && queue.size() == 512, "global overflow cannot exceed cap for close marker");
        queue.disconnect(peers.get(0));
        check(queue.size() == 448, "disconnect discards its 64 pending frames");
        queue.clear();
        check(queue.size() == 0, "server stop clears all retained handlers and frames");

        Object ratePeer = new Object();
        for (int i = 0; i < 512; i++)
        {
            check(queue.offer(ratePeer, frame, null, 0), "token burst admitted when drained");
            check(queue.poll(0).rejection == null, "valid token burst dispatch");
        }
        check(!queue.offer(ratePeer, frame, null, 0), "flood rate rejected even without backlog");
        check(queue.poll(0).rejection != null, "rate flood produces single rejection");
    }

    private static void budgetLimitsAndConcurrency() throws Exception
    {
        NetworkIngressBudget budget = new NetworkIngressBudget(2, 10);
        NetworkIngressBudget.Lease first = budget.tryAcquire(6);
        check(first != null && budget.tryAcquire(5) == null, "byte budget rejects before overcommit");
        NetworkIngressBudget.Lease second = budget.tryAcquire(4);
        check(second != null && budget.tryAcquire(0) == null, "count budget includes queued plus executing");
        first.close(); first.close(); second.close();
        check(budget.getMessages() == 0 && budget.getBytes() == 0, "release idempotent");
        check(budget.tryAcquire(-1) == null && budget.tryAcquire(Long.MAX_VALUE) == null, "signed and overflow byte attacks rejected");

        NetworkIngressBudget concurrent = new NetworkIngressBudget(3, 15);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int n = 0; n < 8; n++)
        {
            Thread worker = new Thread(() ->
            {
                try
                {
                    start.await();
                    for (int i = 0; i < 10000; i++)
                    {
                        NetworkIngressBudget.Lease lease = concurrent.tryAcquire(5);
                        if (lease != null)
                        {
                            if (concurrent.getBytes() > 15 || concurrent.getMessages() > 3)
                            { throw new AssertionError("concurrent overcommit"); }
                            lease.close(); lease.close();
                        }
                    }
                }
                catch (Throwable failure) { error.compareAndSet(null, failure); }
            });
            threads.add(worker); worker.start();
        }
        start.countDown();
        for (Thread worker : threads) { worker.join(); }
        check(error.get() == null && concurrent.getMessages() == 0 && concurrent.getBytes() == 0,
                "80,000 concurrent admissions conserve count/bytes");
    }

    private static void deterministicFrameFuzz()
    {
        Random random = new Random(0x4d49524149L);
        for (int i = 0; i < 5000; i++)
        {
            byte[] frame = new byte[random.nextInt(80)];
            random.nextBytes(frame);
            AtomicInteger calls = new AtomicInteger();
            try
            {
                LegacyPacketCodec.decode(frame, 4096, id -> id == 3,
                        id -> { calls.incrementAndGet(); return new Object(); },
                        (message, in) -> in.skipBytes(in.readUnsignedShort()), message -> { });
            }
            catch (RuntimeException expected) { }
            check(calls.get() == ((frame.length > 0 && frame[0] == 3) ? 1 : 0),
                    "fuzz never reaches decoder through rejected header");
        }
    }

    private static void fails(Runnable operation, String label)
    {
        try { operation.run(); }
        catch (RuntimeException expected) { assertions++; return; }
        throw new AssertionError(label);
    }

    private static void check(boolean condition, String label)
    {
        assertions++;
        if (!condition) { throw new AssertionError(label); }
    }
}
