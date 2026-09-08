package com.seibel.distanthorizons.core.multiplayer.fullData;

import com.seibel.distanthorizons.core.util.MoreObjects;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @see FullDataSplitMessage
 */
public class FullDataPayload implements INetworkObject
{
	private static final AtomicInteger lastBufferId = new AtomicInteger();
	
	public int dtoBufferId;
	public ByteBuf dtoBuffer;
	
	public List<BeaconBeamDTO> beaconBeams;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public FullDataPayload() { }
	public FullDataPayload(@NotNull FullDataSourceV2 fullDataSource, List<BeaconBeamDTO> beaconBeams)
	{
		Objects.requireNonNull(fullDataSource);
		
		this.dtoBufferId = lastBufferId.getAndIncrement();
		
		try
		{
			EDhApiDataCompressionMode compressionMode = Config.Common.LodBuilding.dataCompression.get();
			try (FullDataSourceV2DTO dataSourceDto = FullDataSourceV2DTO.CreateFromDataSource(fullDataSource, compressionMode))
			{
				this.dtoBuffer = Unpooled.buffer();
				dataSourceDto.encode(this.dtoBuffer);
			}
		}
		catch (IOException e)
		{
			if (this.dtoBuffer != null) { this.dtoBuffer.release(); }
			throw new RuntimeException(e);
		}
		catch (RuntimeException | Error e)
		{
			if (this.dtoBuffer != null) { this.dtoBuffer.release(); }
			throw e;
		}
		
		this.beaconBeams = beaconBeams;
	}
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.dtoBufferId);
		this.writeCollection(out, this.beaconBeams);
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.dtoBufferId = in.readInt();
		int count = in.readInt();
		// A beacon encodes three coordinates plus one RGB int (16 bytes).
		if (count < 0 || count > 65536 || count > in.readableBytes() / 16)
		{
			throw new IllegalArgumentException("Invalid LOD beacon count: " + count);
		}
		this.beaconBeams = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
		{
			this.beaconBeams.add(INetworkObject.decodeToInstance(new BeaconBeamDTO(null, null), in));
		}
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("dtoBufferId", this.dtoBufferId)
				.add("dtoBuffer", this.dtoBuffer)
				.add("beaconBeams", this.beaconBeams)
				.toString();
	}
	
}
