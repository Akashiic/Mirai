package com.seibel.distanthorizons.core.world;
/** Logging and diagnostics parent are outside this orchestration test. */
public abstract class AbstractDhWorld implements IDhWorld
{
    protected static final Logger LOGGER = new Logger();
    public final EWorldEnvironment environment;
    protected AbstractDhWorld(EWorldEnvironment environment) { this.environment = environment; }
    @Override public abstract void close();
    public static final class Logger { public void info(String message) { } }
}
