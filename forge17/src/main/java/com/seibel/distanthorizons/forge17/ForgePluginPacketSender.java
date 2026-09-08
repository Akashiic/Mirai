package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.common.AbstractPluginPacketSender;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.event.internal.ProtocolErrorInternalEvent;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.MessageRegistry;
import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.base.LevelInitMessage;
import com.seibel.distanthorizons.core.network.messages.base.RequestLevelInitMessage;
import com.seibel.distanthorizons.core.network.messages.base.SessionConfigMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataPartialUpdateMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.network.messages.requests.CancelMessage;
import com.seibel.distanthorizons.core.network.messages.requests.ExceptionMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.forge17.network.BoundedPacketQueue;
import com.seibel.distanthorizons.forge17.network.LegacyPacketCodec;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ForgePluginPacketSender extends AbstractPluginPacketSender
{
    private static final DhLogger LOGGER = new DhLoggerBuilder().maxCountPerSecond(1).build();
    private static volatile BiConsumer<IServerPlayerWrapper, AbstractNetworkMessage> consumerPacket;
    private static boolean registered;
    private static final BoundedPacketQueue<NetHandlerPlayServer> SERVER_INGRESS = new BoundedPacketQueue<>();
    private static final Map<Object, Boolean> REJECTED_CLIENT_PEERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static final SimpleNetworkWrapper PLUGIN_CHANNEL = NetworkRegistry.INSTANCE
            .newSimpleChannel(AbstractPluginPacketSender.WRAPPER_PACKET_RESOURCE);

    public static void setPacketHandler(Consumer<AbstractNetworkMessage> consumer)
    { setPacketHandler((player, message) -> consumer.accept(message)); }

    public static synchronized void setPacketHandler(BiConsumer<IServerPlayerWrapper, AbstractNetworkMessage> consumer)
    {
        consumerPacket = Objects.requireNonNull(consumer);
        if (!registered)
        {
            PLUGIN_CHANNEL.registerMessage(MessageWrapper.Handler.class, MessageWrapper.class, 0, Side.CLIENT);
            PLUGIN_CHANNEL.registerMessage(MessageWrapper.Handler.class, MessageWrapper.class, 0, Side.SERVER);
            FMLCommonHandler.instance().bus().register(new IngressEvents());
            registered = true;
        }
    }

    @Override
    public void sendToServer(AbstractNetworkMessage message)
    { PLUGIN_CHANNEL.sendToServer(new MessageWrapper(message, Side.SERVER)); }

    @Override
    public void sendToClient(EntityPlayerMP serverPlayer, AbstractNetworkMessage message)
    { PLUGIN_CHANNEL.sendTo(new MessageWrapper(message, Side.CLIENT), serverPlayer); }

    public static void clearServerQueue() { SERVER_INGRESS.clear(); }

    private static boolean allowed(int id, Side receivingSide)
    {
        MessageRegistry registry = MessageRegistry.INSTANCE;
        if (id == registry.getMessageId(CloseReasonMessage.class)
                || id == registry.getMessageId(SessionConfigMessage.class)
                || id == registry.getMessageId(CancelMessage.class))
        {
            return true;
        }
        if (receivingSide == Side.SERVER)
        {
            // Never instantiate server-to-client DTO/split messages on a server.
            return id == registry.getMessageId(RequestLevelInitMessage.class)
                    || id == registry.getMessageId(FullDataSourceRequestMessage.class);
        }
        return id == registry.getMessageId(LevelInitMessage.class)
                || id == registry.getMessageId(ExceptionMessage.class)
                || id == registry.getMessageId(FullDataSourceResponseMessage.class)
                || id == registry.getMessageId(FullDataPartialUpdateMessage.class)
                || id == registry.getMessageId(FullDataSplitMessage.class);
    }

    private static AbstractNetworkMessage decode(byte[] frame, Side side)
    {
        return LegacyPacketCodec.decode(frame, side == Side.SERVER
                        ? LegacyPacketCodec.MAX_SERVERBOUND_BYTES : LegacyPacketCodec.MAX_FRAME_BYTES,
                id -> allowed(id, side), MessageRegistry.INSTANCE::createMessage,
                AbstractNetworkMessage::decode, ForgePluginPacketSender::releaseRejectedMessage);
    }

    private static void releaseRejectedMessage(AbstractNetworkMessage message)
    {
        if (message instanceof FullDataSplitMessage)
        {
            FullDataSplitMessage split = (FullDataSplitMessage) message;
            if (split.buffer != null && split.buffer.refCnt() > 0)
            {
                split.buffer.release();
            }
        }
    }

    private static ProtocolErrorInternalEvent protocolError(String reason)
    {
        // Fixed diagnostic strings only: no attacker-supplied payload or hex dumps.
        LOGGER.warn(reason);
        return new ProtocolErrorInternalEvent(new IllegalArgumentException(reason), null, true);
    }

    public static class MessageWrapper implements IMessage
    {
        private AbstractNetworkMessage message;
        private Side destination;
        private byte[] frame;

        public MessageWrapper() { /* Forge reflection */ }
        private MessageWrapper(AbstractNetworkMessage message, Side destination)
        {
            this.message = Objects.requireNonNull(message);
            this.destination = destination;
        }

        @Override
        public void fromBytes(ByteBuf buf)
        {
            // Side is unavailable here. Only copy a bounded raw frame; direction
            // validation and all DTO construction happen later in the handler.
            this.frame = LegacyPacketCodec.copyFrame(buf);
        }

        @Override
        public void toBytes(ByteBuf buf)
        {
            int id = MessageRegistry.INSTANCE.getMessageId(this.message);
            if (!allowed(id, this.destination))
            {
                throw new IllegalArgumentException("Attempted plugin message in the wrong direction");
            }
            LegacyPacketCodec.encode(buf, this.message, id,
                    this.destination == Side.SERVER ? LegacyPacketCodec.MAX_SERVERBOUND_BYTES
                            : LegacyPacketCodec.MAX_FRAME_BYTES, AbstractNetworkMessage::encode);
        }

        public static class Handler implements IMessageHandler<MessageWrapper, IMessage>
        {
            @Override
            public IMessage onMessage(MessageWrapper wrapper, MessageContext context)
            {
                if (consumerPacket == null)
                {
                    return null;
                }
                if (context.side == Side.SERVER)
                {
                    boolean accepted = LegacyPacketCodec.accepts(wrapper.frame,
                            LegacyPacketCodec.MAX_SERVERBOUND_BYTES, id -> allowed(id, Side.SERVER));
                    SERVER_INGRESS.offer(context.getServerHandler(), wrapper.frame,
                            accepted ? null : "Rejected serverbound plugin frame size or direction", System.nanoTime());
                }
                else if (!REJECTED_CLIENT_PEERS.containsKey(context.netHandler))
                {
                    AbstractNetworkMessage decoded;
                    try
                    {
                        decoded = decode(wrapper.frame, Side.CLIENT);
                    }
                    catch (RuntimeException exception)
                    {
                        REJECTED_CLIENT_PEERS.put(context.netHandler, Boolean.TRUE);
                        decoded = protocolError("Rejected malformed clientbound plugin frame");
                    }
                    consumerPacket.accept(null, decoded);
                }
                return null;
            }
        }
    }

    public static final class IngressEvents
    {
        @SubscribeEvent
        public void serverTick(TickEvent.ServerTickEvent event)
        {
            if (event.phase != TickEvent.Phase.END)
            {
                return;
            }
            long started = System.nanoTime();
            for (int count = 0; count < 64; count++)
            {
                BoundedPacketQueue.Entry<NetHandlerPlayServer> entry = SERVER_INGRESS.poll(System.nanoTime());
                if (entry == null)
                {
                    break;
                }
                if (!entry.peer.netManager.isChannelOpen())
                {
                    SERVER_INGRESS.disconnect(entry.peer);
                    continue;
                }
                AbstractNetworkMessage decoded;
                if (entry.rejection != null)
                {
                    decoded = protocolError(entry.rejection);
                }
                else
                {
                    try
                    {
                        decoded = decode(entry.frame, Side.SERVER);
                    }
                    catch (RuntimeException exception)
                    {
                        SERVER_INGRESS.reject(entry.peer);
                        decoded = protocolError("Rejected malformed serverbound plugin frame");
                    }
                }
                consumerPacket.accept(ServerPlayerWrapper.getWrapper(entry.peer.playerEntity), decoded);
                if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(2))
                {
                    break;
                }
            }
        }

        @SubscribeEvent
        public void serverDisconnected(FMLNetworkEvent.ServerDisconnectionFromClientEvent event)
        {
            if (event.handler instanceof NetHandlerPlayServer)
            {
                SERVER_INGRESS.disconnect((NetHandlerPlayServer) event.handler);
            }
        }

        @SubscribeEvent
        public void clientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event)
        { REJECTED_CLIENT_PEERS.remove(event.handler); }
    }
}
