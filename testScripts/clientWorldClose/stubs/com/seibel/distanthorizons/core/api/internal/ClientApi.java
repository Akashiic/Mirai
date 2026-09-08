package com.seibel.distanthorizons.core.api.internal;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
public final class ClientApi
{
    public static final ClientApi INSTANCE = new ClientApi();
    public int loadWaitingCalls;
    public void loadWaitingChunksForLevel(IClientLevelWrapper level) { this.loadWaitingCalls++; }
    public void queueSlowChatMessage(String message) { }
}
