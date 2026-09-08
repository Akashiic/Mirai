package com.seibel.distanthorizons.core.api.internal;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
public final class ClientPluginChannelApi
{
    public int resetCalls;
    public Throwable resetFailure;
    public void onJoinServer(Object session) { }
    public boolean allowLevelLoading(IClientLevelWrapper wrapper) { return true; }
    public void reset()
    {
        this.resetCalls++;
        if (this.resetFailure instanceof RuntimeException) throw (RuntimeException) this.resetFailure;
        if (this.resetFailure instanceof Error) throw (Error) this.resetFailure;
    }
}
