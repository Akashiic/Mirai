package com.seibel.distanthorizons.core.network.session;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
/** Boundary fake: manager logic is real; Minecraft/network handlers are not exercised. */
public final class NetworkSession
{
    public final List<Integer> handled = Collections.synchronizedList(new ArrayList<Integer>());
    public volatile Consumer<AbstractNetworkMessage> handler = message -> { };
    private volatile boolean closed;
    public boolean isClosed() { return this.closed; }
    public void close(Throwable reason) { this.closed = true; }
    public void tryHandleMessage(AbstractNetworkMessage message)
    {
        if (this.closed) return;
        this.handled.add(message.id);
        this.handler.accept(message);
    }
}
