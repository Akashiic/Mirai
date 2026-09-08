package com.seibel.distanthorizons.core.multiplayer.server;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
/** Failure injection fake; manager implementation is tested separately by serverQueue. */
public final class ServerPlayerStateManager
{
    public int closeCalls;
    public int unregisterCalls;
    public RuntimeException closeFailure;
    public void close() { this.closeCalls++; if (this.closeFailure != null) throw this.closeFailure; }
    public ServerPlayerState registerJoinedPlayer(IServerPlayerWrapper player) { return new ServerPlayerState(); }
    public void unregisterLeftPlayer(IServerPlayerWrapper player) { this.unregisterCalls++; }
    public void handlePluginMessagesFromQueue(ServerPlayerState player) { }
}
