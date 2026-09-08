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

package com.seibel.distanthorizons.core.file.structure;

import com.google.common.net.PercentEscaper;
import com.seibel.distanthorizons.api.interfaces.override.levelHandling.IDhApiSaveStructure;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.api.enums.config.EDhApiServerFolderNameMode;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.ParsedIp;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.coreapi.util.StringUtil;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Designed for the Client_Only environment.
 */
public class ClientOnlySaveStructure implements ISaveStructure
{
	public static final String SERVER_DATA_FOLDER_NAME = "Distant_Horizons_server_data";
	public static final String REPLAY_SERVER_FOLDER_NAME = "REPLAY";
	public static final String INVALID_FILE_CHARACTERS_REGEX = "[\\\\/:*?\"<>|]";
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	
	// File values do not retain their wrappers. Keep the existing equals/hashCode
	// semantics while allowing unloaded wrappers and their WorldClient to be collected.
	private final Map<ILevelWrapper, File> levelWrapperToFileMap = Collections.synchronizedMap(new WeakHashMap<>());
	private final UnidentifiedClientCache unidentifiedCache = new UnidentifiedClientCache();
	private volatile boolean closed;
	private final List<CompletableFuture<Void>> closeDependencies = new ArrayList<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ClientOnlySaveStructure() { }
	
	
	
	//================//
	// folder methods //
	//================//
	
	@Override
	public synchronized File getSaveFolder(ILevelWrapper levelWrapper)
	{
		if (this.closed) { throw new IllegalStateException("Client save structure is closed"); }
		File resolvedFolder = this.levelWrapperToFileMap.computeIfAbsent(levelWrapper, (newLevelWrapper) ->
		{
			File saveFolder;
			
			// Use the server provided key if one was provided
			if (newLevelWrapper instanceof IServerKeyedClientLevel)
			{
				IServerKeyedClientLevel keyedClientLevel = (IServerKeyedClientLevel) newLevelWrapper;
				LOGGER.info("Loading level [" + newLevelWrapper.getDhIdentifier() + "] with key: [" + keyedClientLevel.getServerLevelKey() + "].");
				
				String serverKey = keyedClientLevel.getServerKey();
				if (serverKey.isEmpty())
				{
					serverKey = getEndpointFolderName();
				}
				
				// This world was identified by the server directly, so we can know for sure which folder to use.				
				saveFolder = getSaveFolderByLevelId(serverKey, keyedClientLevel.getServerLevelKey());
			}
			else
			{
				if (MC_CLIENT.connectedToReplay())
				{
					saveFolder = getSaveFolderByLevelId(REPLAY_SERVER_FOLDER_NAME, newLevelWrapper.getDhIdentifier());
				}
				else
				{
					Object mcWorld = newLevelWrapper.getWrappedMcObject();
					saveFolder = this.unidentifiedCache.folder(MC_CLIENT.getCurrentServerIp(),
							mcWorld != null ? mcWorld : newLevelWrapper, newLevelWrapper.getDhIdentifier());
					LOGGER.info("No stable backend world key received. Using temporary, isolated LOD cache [" + saveFolder
							+ "]; these LODs are not reused across world instances or connections.");
				}
			}
			
			// Allow API users to override the save folder
			IDhApiSaveStructure saveStructureOverride = OverrideInjector.INSTANCE.get(IDhApiSaveStructure.class);
			if (saveStructureOverride != null)
			{
				File overrideFile = saveStructureOverride.overrideFilePath(saveFolder, newLevelWrapper);
				if (overrideFile != null)
				{
					LOGGER.info("Save folder overridden from ["+saveFolder.getPath()+"] -> ["+overrideFile.getPath()+"].");
					saveFolder = overrideFile;
				}
			}
			
			return saveFolder;
		});
		// An API override may re-enter close() while the mapping function is running.
		if (this.closed)
		{
			this.levelWrapperToFileMap.clear();
			throw new IllegalStateException("Client save structure was closed during folder resolution");
		}
		return resolvedFolder;
	}
	
	@Override
	public File getPre23SaveFolder(ILevelWrapper levelWrapper)
	{
		// Allow API users to override the save folder
		IDhApiSaveStructure saveStructureOverride = OverrideInjector.INSTANCE.get(IDhApiSaveStructure.class);
		if (saveStructureOverride != null)
		{
			return this.getSaveFolder(levelWrapper);
		}
		
		// Never import a name-only cache into an unidentified proxy backend automatically.
		if (!MC_CLIENT.connectedToReplay()) { return this.getSaveFolder(levelWrapper); }
		return getSaveFolderByLevelId(REPLAY_SERVER_FOLDER_NAME, levelWrapper.getDimensionType().getName());
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	/** Returns true if the given folder holds valid Lod Dimension data */
	private static ArrayList<File> getValidDhDimensionFolders(File potentialFolder)
	{
		ArrayList<File> subDimSaveFolders = new ArrayList<>();
		
		if (!potentialFolder.isDirectory())
		{
			// a valid level folder needs to be a folder
			return subDimSaveFolders;
		}
		
		
		File[] potentialLevelFolders = potentialFolder.listFiles();
		if (potentialLevelFolders != null)
		{
			// check each level folder
			for (File potentialFile : potentialLevelFolders)
			{
				if (potentialFile.isDirectory())
				{
					// check if this is a valid DH level folder
					File[] dataFolders = potentialFile.listFiles();
					if (dataFolders != null)
					{
						boolean isValidDhLevelFolder = false;
						for (File dataFolder : dataFolders)
						{
							// look for the DH database file
							if (dataFolder.getName().equalsIgnoreCase(ISaveStructure.DATABASE_NAME))
							{
								isValidDhLevelFolder = true;
								break;
							}
						}
						
						if (isValidDhLevelFolder)
						{
							subDimSaveFolders.add(potentialFile);
						}
					}
				}
			}
		}
		
		return subDimSaveFolders;
	}
	
	
	private static File getSaveFolderByLevelId(String folderName, String dimensionName)
	{
		String path = MC_SHARED.getInstallationDirectory().getPath() + File.separatorChar
				+ SERVER_DATA_FOLDER_NAME + File.separatorChar
				+ folderName + File.separatorChar
				+ dimensionName.replaceAll(":", "@@");
		
		return new File(path);
	}
	
	/** Generated from the server the client is currently connected to. */
	private static String getEndpointFolderName()
	{
		// Endpoint identity is independent of the legacy display naming option.
		return "endpoint-" + UnidentifiedClientCache.endpointKey(MC_CLIENT.getCurrentServerIp());
	}

	/** Legacy display naming retained for API/config compatibility, not trusted as world identity. */
	private static String getServerFolderName()
	{
		// if connected to a replay we won't have any server info
		// use the dedicated replay server folder
		if (MC_CLIENT.connectedToReplay())
		{
			return REPLAY_SERVER_FOLDER_NAME;
		}
		
		
		// parse the current server's IP
		ParsedIp parsedIp = new ParsedIp(MC_CLIENT.getCurrentServerIp());
		String serverIpCleaned = parsedIp.ip.replaceAll(INVALID_FILE_CHARACTERS_REGEX, "");
		String serverPortCleaned = parsedIp.port != null ? parsedIp.port.replaceAll(INVALID_FILE_CHARACTERS_REGEX, "") : "";
		
		
		// determine the auto folder name format
		EDhApiServerFolderNameMode folderNameMode = Config.Client.Advanced.Multiplayer.serverFolderNameMode.get();
		String serverName = MC_CLIENT.getCurrentServerName().replaceAll(INVALID_FILE_CHARACTERS_REGEX, "");
		String serverMcVersion = MC_CLIENT.getCurrentServerVersion().replaceAll(INVALID_FILE_CHARACTERS_REGEX, "");
		
		
		// generate the folder name
		String folderName;
		switch (folderNameMode)
		{
			default:
			case NAME_ONLY:
				folderName = serverName;
				break;
			case IP_ONLY:
				folderName = serverIpCleaned;
				break;
			
			case NAME_IP:
				folderName = serverName + ", IP " + serverIpCleaned;
				break;
			case NAME_IP_PORT:
				folderName = serverName + ", IP " + serverIpCleaned + (serverPortCleaned.length() != 0 ? ("-" + serverPortCleaned) : "");
				break;
			case NAME_IP_PORT_MC_VERSION:
				folderName = serverName + ", IP " + serverIpCleaned + (serverPortCleaned.length() != 0 ? ("-" + serverPortCleaned) : "") + ", GameVersion " + serverMcVersion;
				break;
		}
		
		// PercentEscaper makes the characters all part of the standard alphameric character set
		// This fixes some issues when the server is named something in other languages
		return new PercentEscaper("", true).escape(folderName);
	}
	
	
	
	//==================//
	// override methods //
	//==================//
	
	@Override
	public synchronized void close()
	{
		if (this.closed) { return; }
		this.closed = true;
		this.levelWrapperToFileMap.clear();
		CompletableFuture<Void> providersClosed = CompletableFuture.allOf(this.closeDependencies.toArray(new CompletableFuture[0]));
		this.closeDependencies.clear();
		providersClosed.whenCompleteAsync((ignored, closeError) ->
		{
			if (closeError != null)
			{
				LOGGER.warn("A provider did not close successfully; its temporary LOD files are retained for OS cleanup.", closeError);
				return;
			}
			try { this.unidentifiedCache.close(); }
			catch (java.io.IOException error)
			{
				LOGGER.warn("Unable to remove this session's temporary LOD cache; it remains in the OS temporary directory.", error);
			}
		});
	}

	/** Delay removal of owned temporary files until every provider has actually released its handles. */
	public synchronized void registerCloseFuture(CompletableFuture<Void> future)
	{
		if (this.closed) { throw new IllegalStateException("Client save structure is closed"); }
		this.closeDependencies.add(future);
	}
	
	@Override
	public String toString()
	{
		synchronized (this.levelWrapperToFileMap)
		{
			return "[" + this.getClass().getSimpleName() + "@(" + StringUtil.join(";", this.levelWrapperToFileMap.values()) + ")]";
		}
	}
	
}
