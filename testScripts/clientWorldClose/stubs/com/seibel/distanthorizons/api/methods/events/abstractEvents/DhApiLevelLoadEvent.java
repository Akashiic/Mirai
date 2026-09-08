package com.seibel.distanthorizons.api.methods.events.abstractEvents;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
public final class DhApiLevelLoadEvent
{
    public static final class EventParam
    {
        public final ILevelWrapper wrapper;
        public EventParam(ILevelWrapper wrapper) { this.wrapper = wrapper; }
    }
}
