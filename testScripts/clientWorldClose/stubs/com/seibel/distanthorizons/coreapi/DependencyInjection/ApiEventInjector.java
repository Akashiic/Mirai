package com.seibel.distanthorizons.coreapi.DependencyInjection;
import java.util.function.Consumer;
public final class ApiEventInjector
{
    public static final ApiEventInjector INSTANCE = new ApiEventInjector();
    public Consumer<Object> handler = event -> { };
    public void fireAllEvents(Class<?> type, Object event) { this.handler.accept(event); }
}
