package com.seibel.distanthorizons.forge17.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The existing Forge 1.7.10 wire format is one unsigned message ID followed by
 * its body. It does NOT have the common codec's protocol-version header.
 * Preserve that format; a versioned replacement requires explicit negotiation.
 */
public final class LegacyPacketCodec
{
    // SimpleNetworkWrapper adds one discriminator byte. Both together fit the
    // signed-short custom-payload limit even on unpatched Forge/Crucible paths.
    public static final int MAX_FRAME_BYTES = 32766;
    public static final int MAX_SERVERBOUND_BYTES = 4096;

    private LegacyPacketCodec() { }

    /** Returns null without allocating a payload copy when the frame is invalid. */
    public static byte[] copyFrame(ByteBuf in)
    {
        int length = in.readableBytes();
        try
        {
            if (length < 1 || length > MAX_FRAME_BYTES)
            {
                return null;
            }
            byte[] frame = new byte[length];
            in.readBytes(frame);
            return frame;
        }
        finally
        {
            // The Forge pipeline owns this buffer. Never retain or release it.
            in.readerIndex(in.writerIndex());
        }
    }

    public static boolean accepts(byte[] frame, int maxBytes, IntPredicate allowedId)
    {
        return frame != null && frame.length >= 1 && frame.length <= maxBytes
                && allowedId.test(frame[0] & 255);
    }

    /** The allowlist is consulted before constructing any message/DTO. */
    public static <T> T decode(byte[] frame, int maxBytes, IntPredicate allowedId,
            IntFunction<T> factory, BiConsumer<T, ByteBuf> decoder, Consumer<T> cleanup)
    {
        if (!accepts(frame, maxBytes, allowedId))
        {
            throw new IllegalArgumentException("Rejected legacy plugin frame size or direction");
        }
        ByteBuf in = Unpooled.wrappedBuffer(frame);
        T message = null;
        boolean success = false;
        try
        {
            message = factory.apply(in.readUnsignedByte());
            decoder.accept(message, in);
            if (in.isReadable())
            {
                throw new IllegalArgumentException("Trailing bytes in legacy plugin frame");
            }
            success = true;
            return message;
        }
        finally
        {
            try
            {
                if (!success && message != null)
                {
                    cleanup.accept(message);
                }
            }
            finally
            {
                in.release();
            }
        }
    }

    /** Encode into a bounded temporary buffer: failures cannot leave a partial frame. */
    public static <T> void encode(ByteBuf out, T message, int messageId,
            int maxBytes, BiConsumer<T, ByteBuf> encoder)
    {
        if (messageId < 1 || messageId > 255 || maxBytes < 1 || maxBytes > MAX_FRAME_BYTES)
        {
            throw new IllegalArgumentException("Invalid legacy plugin frame parameters");
        }
        ByteBuf bounded = Unpooled.buffer(Math.min(256, maxBytes), maxBytes);
        try
        {
            bounded.writeByte(messageId);
            encoder.accept(message, bounded);
            out.writeBytes(bounded);
        }
        finally
        {
            bounded.release();
        }
    }
}
