package com.seibel.distanthorizons.coreapi.DependencyInjection;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import java.util.function.Consumer;
public final class ApiEventInjector
{
    public static final ApiEventInjector INSTANCE = new ApiEventInjector();
    public Consumer<DhApiLevelUnloadEvent.EventParam> handler = event -> { };
    public void fireAllEvents(Class<?> eventType, DhApiLevelUnloadEvent.EventParam event) { this.handler.accept(event); }
}
