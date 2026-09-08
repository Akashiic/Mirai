package com.seibel.distanthorizons.core.world;
import java.util.List;
public abstract class AbstractDhWorld implements IDhWorld
{
    protected static final Logger LOGGER = new Logger();
    public final EWorldEnvironment environment;
    protected AbstractDhWorld(EWorldEnvironment environment) { this.environment = environment; }
    public void addDebugMenuStringsToList(List<String> messages) { }
    @Override public abstract void close();
    public static final class Logger
    {
        public void info(String message) { }
        public void debug(String message) { }
        public void fatal(String message, Throwable error) { }
    }
}
