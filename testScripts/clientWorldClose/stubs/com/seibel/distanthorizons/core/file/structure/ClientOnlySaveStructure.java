package com.seibel.distanthorizons.core.file.structure;
public final class ClientOnlySaveStructure
{
    public int closeCalls;
    public Runnable closeHook = () -> { };
    public void close() { this.closeCalls++; this.closeHook.run(); }
}
