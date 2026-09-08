package com.seibel.distanthorizons.core.level;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import java.util.concurrent.atomic.AtomicInteger;
/** Only cleanup callback behavior is injected; world orchestration remains production code. */
public final class DhClientLevel implements IDhClientLevel
{
    public static java.util.function.Consumer<DhClientLevel> constructorHook = level -> { };
    public static DhClientLevel lastCreated;
    public final IClientLevelWrapper wrapper;
    public final AtomicInteger closeCalls = new AtomicInteger();
    public Throwable closeFailure;
    public Runnable closeHook = () -> { };
    public DhClientLevel(ClientOnlySaveStructure save, IClientLevelWrapper wrapper, ClientNetworkState network)
    { this.wrapper = wrapper; lastCreated = this; constructorHook.accept(this); }
    public IClientLevelWrapper getClientLevelWrapper() { return this.wrapper; }
    public IClientLevelWrapper getLevelWrapper() { return this.wrapper; }
    public void clientTick() { }
    public void close()
    {
        this.closeCalls.incrementAndGet();
        this.closeHook.run();
        if (this.closeFailure instanceof RuntimeException) throw (RuntimeException) this.closeFailure;
        if (this.closeFailure instanceof Error) throw (Error) this.closeFailure;
    }
}
