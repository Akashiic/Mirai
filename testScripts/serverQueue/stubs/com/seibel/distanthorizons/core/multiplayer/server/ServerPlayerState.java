package com.seibel.distanthorizons.core.multiplayer.server;
import com.seibel.distanthorizons.core.multiplayer.fullData.SharedBandwidthLimit;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
public final class ServerPlayerState
{
    public final NetworkSession networkSession = new NetworkSession();
    private final IServerPlayerWrapper player;
    public volatile int closeCalls;
    public volatile RuntimeException closeFailure;
    public ServerPlayerState(IServerPlayerWrapper player, SharedBandwidthLimit ignored) { this.player = player; }
    public IServerPlayerWrapper getServerPlayer() { return this.player; }
    public boolean isReady() { return true; }
    public void close() { this.closeCalls++; if (this.closeFailure != null) throw this.closeFailure; }
}
