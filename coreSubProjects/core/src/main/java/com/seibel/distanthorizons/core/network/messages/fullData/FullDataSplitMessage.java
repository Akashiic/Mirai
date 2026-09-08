/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.network.messages.fullData;

import com.seibel.distanthorizons.core.util.MoreObjects;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayloadSender;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Timer;

/**
 * Used to send part of a {@link FullDataPayload}.
 * 
 * @see FullDataPayload
 */
public class FullDataSplitMessage extends AbstractNetworkMessage
{
	public int bufferId;
	public ByteBuf buffer;
	public boolean isFirst;
	private int payloadSize;
	private byte[] outboundBytes;
	/** Remains valid after the receiver has released the fragment. */
	public int getPayloadSize() { return this.payloadSize; }
	
	
	//==============//
	// constructors //
	//==============//
	
	public FullDataSplitMessage() { }
	public FullDataSplitMessage(int bufferId, ByteBuf buffer, boolean isFirst)
	{
		this.bufferId = bufferId;
		this.buffer = buffer;
		this.isFirst = isFirst;
		this.payloadSize = buffer.readableBytes();
	}

	/** Snapshot for asynchronous encoders: no retained reference to a shared DTO. */
	public static FullDataSplitMessage copyForSending(int bufferId, ByteBuf source, boolean isFirst)
	{
		int length = source.readableBytes();
		if (length <= 0 || length > FullDataPayloadSender.FULL_DATA_SPLIT_SIZE_IN_BYTES)
		{
			throw new IllegalArgumentException("Invalid outgoing LOD fragment size: " + length);
		}
		FullDataSplitMessage message = new FullDataSplitMessage();
		message.bufferId = bufferId;
		message.isFirst = isFirst;
		message.payloadSize = length;
		message.outboundBytes = new byte[length];
		source.getBytes(source.readerIndex(), message.outboundBytes);
		return message;
	}
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.bufferId);
		
		if (this.outboundBytes != null)
		{
			out.writeInt(this.outboundBytes.length);
			out.writeBytes(this.outboundBytes);
		}
		else
		{
			out.writeInt(this.buffer.writerIndex());
			out.writeBytes(this.buffer, 0, this.buffer.writerIndex());
		}

		out.writeBoolean(this.isFirst);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.bufferId = in.readInt();
		
		int bufferSize = in.readInt();
		if (bufferSize <= 0 || bufferSize > FullDataPayloadSender.FULL_DATA_SPLIT_SIZE_IN_BYTES
			|| bufferSize > in.readableBytes() - 1)
		{
			throw new IllegalArgumentException("Invalid LOD fragment size: " + bufferSize);
		}
		ByteBuf slice = in.readSlice(bufferSize);
		this.isFirst = in.readBoolean();
		this.buffer = Unpooled.copiedBuffer(slice);
		this.payloadSize = bufferSize;
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public MoreObjects.ToStringHelper toStringHelper()
	{
		return super.toStringHelper()
				.add("bufferId", this.bufferId)
				.add("buffer", this.buffer)
				.add("isFirst", this.isFirst);
	}
	
}
