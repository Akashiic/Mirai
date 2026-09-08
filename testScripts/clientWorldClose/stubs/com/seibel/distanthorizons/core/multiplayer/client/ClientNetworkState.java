package com.seibel.distanthorizons.core.multiplayer.client;
import java.util.List;
/** Configurable network cleanup boundary, not the real network implementation. */
public final class ClientNetworkState
{
    public int closeCalls;
    public Throwable closeFailure;
    public Runnable closeHook = () -> { };
    public Object getSession() { return this; }
    public void sendLevelInitRequest(String dimension) { }
    public void addDebugMenuStringsToList(List<String> messages) { }
    public void close()
    {
        this.closeCalls++;
        this.closeHook.run();
        if (this.closeFailure instanceof RuntimeException) throw (RuntimeException) this.closeFailure;
        if (this.closeFailure instanceof Error) throw (Error) this.closeFailure;
    }
}
