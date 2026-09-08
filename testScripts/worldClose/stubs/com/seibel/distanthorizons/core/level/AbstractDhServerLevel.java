package com.seibel.distanthorizons.core.level;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
/** Only the world orchestration is under test; actual level cleanup is a fault-injection boundary. */
public abstract class AbstractDhServerLevel implements IDhServerLevel
{
    public abstract IServerLevelWrapper getServerLevelWrapper();
    public abstract void close();
    public void addPlayer(IServerPlayerWrapper player) { }
    public void removePlayer(IServerPlayerWrapper player) { }
    public void registerNetworkHandlers(ServerPlayerState state) { }
}
