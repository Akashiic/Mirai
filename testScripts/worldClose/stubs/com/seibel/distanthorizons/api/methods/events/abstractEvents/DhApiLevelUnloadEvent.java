package com.seibel.distanthorizons.api.methods.events.abstractEvents;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
public final class DhApiLevelUnloadEvent
{
    public static final class EventParam
    {
        public final IServerLevelWrapper wrapper;
        public EventParam(IServerLevelWrapper wrapper) { this.wrapper = wrapper; }
    }
}
