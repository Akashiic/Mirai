package com.seibel.distanthorizons.common.commands;

import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
#endif
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.generation.PregenManager;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.world.DhServerWorld;

#if MC_VER <= MC_1_12_2
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
#else
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
#endif

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;


public class PregenCommand extends AbstractDhCommand
{
	private PregenManager getPregenManager()
	{
		DhServerWorld world = (DhServerWorld) Objects.requireNonNull(SharedApi.getAbstractDhWorld());
		return world.getPregenManager();
	}
	
	#if MC_VER <= MC_1_12_2
	private static final String USAGE = "Usage: /dh pregen start <dimension> <blockX> <blockZ> [chunkRadius=32] [existing|generate] | /dh pregen <status|stop>";
	
	public void execute(MinecraftServer server, ICommandSender sender, String[] args)
	{
		if (args.length < 2)
		{
			sendMessage(sender, USAGE);
			return;
		}
		
		switch (args[1])
		{
			case "status":
			{
				String statusString = this.getPregenManager().getStatusString();
				sendMessage(sender, statusString != null ? statusString : "Pregen is not running");
				break;
			}
			case "start":
			{
				if (args.length < 5 || args.length > 7)
				{
					sendMessage(sender, USAGE);
					return;
				}
				
				try
				{
					String dimensionName = args[2];
					int x = Integer.parseInt(args[3]);
					int z = Integer.parseInt(args[4]);
					int chunkRadius = args.length >= 6 ? Integer.parseInt(args[5]) : 32;
					// 1.7.10 defaults to converting saved chunks only: the generator path has not passed the Crucible gates
					PregenManager.EPregenMode mode = args.length >= 7 ? PregenManager.EPregenMode.parse(args[6]) : PregenManager.EPregenMode.EXISTING;
					if (mode == null)
					{
						sendMessage(sender, "Unknown mode: " + args[6] + " (use 'existing' or 'generate')");
						return;
					}
					
					// find the world by dimension name
					WorldServer world = null;
					#if MC_VER <= MC_1_7_10
					for (WorldServer possibleWorld : server.worldServers)
					#else
					for (WorldServer possibleWorld : server.worlds)
					#endif
					{
						#if MC_VER <= MC_1_7_10
						if (possibleWorld.provider.getDimensionName().equals(dimensionName) 
							|| String.valueOf(possibleWorld.provider.dimensionId).equals(dimensionName))
						#else
						if (possibleWorld.provider.getDimensionType().getName().equals(dimensionName))
						#endif
						{
							world = possibleWorld;
							break;
						}
					}
					
					if (world == null)
					{
						sendMessage(sender, "Unknown dimension: " + dimensionName);
						return;
					}
					
					PregenManager.RunningPregen run = this.getPregenManager().start(
						ServerLevelWrapper.getWrapper(world),
						new DhBlockPos2D(x, z),
						chunkRadius,
						mode
					);
					
					sendMessage(sender, "Starting pregen (" + mode.name().toLowerCase() + ", " + run.task.getSectionCount() + " sections"
						+ (run.resumedFromIndex > 0 ? ", resuming at section " + run.resumedFromIndex : "")
						+ "). Progress: /dh pregen status and the server console.");
					if (mode == PregenManager.EPregenMode.GENERATE)
					{
						sendMessage(sender, "Mode 'generate' creates missing terrain with the world generator.");
					}
					
					final ICommandSender finalSender = sender;
					run.task.whenComplete((result, throwable) -> {
						String message;
						if (throwable instanceof CancellationException)
						{
							message = "Pregen is cancelled; start it again with the same arguments to resume.";
						}
						else if (throwable != null)
						{
							message = "Pregen failed: " + throwable.getMessage();
						}
						else
						{
							message = "Pregen is complete: " + run.task.getCountersString();
						}
						// completion happens on the pregen thread; chat must be sent from the server thread
						#if MC_VER <= MC_1_7_10
						ServerThreadTaskHandler.INSTANCE.queueTask(() -> { sendMessage(finalSender, message); return null; });
						#else
						sendMessage(finalSender, message);
						#endif
					});
				}
				catch (NumberFormatException e)
				{
					sendMessage(sender, "Invalid number format");
				}
				catch (IllegalArgumentException | IllegalStateException e)
				{
					sendMessage(sender, "Pregen not started: " + e.getMessage());
				}
				break;
			}
			case "stop":
			{
				CompletableFuture<Void> runningPregen = this.getPregenManager().getRunningPregen();
				if (runningPregen == null)
				{
					sendMessage(sender, "Pregen is not running");
					return;
				}
				runningPregen.cancel(true);
				break;
			}
			default:
				sendMessage(sender, "Unknown subcommand: " + args[1]);
		}
	}
    #else
	@Override
	public LiteralArgumentBuilder<CommandSourceStack> buildCommand()
	{
		LiteralArgumentBuilder<CommandSourceStack> statusCommand = literal("status")
				.executes(this::pregenStatus);
		
		LiteralArgumentBuilder<CommandSourceStack> startCommand = literal("start")
				.then(argument("dimension", DimensionArgument.dimension())
						.then(argument("origin", ColumnPosArgument.columnPos())
								.then(argument("chunkRadius", integer(32))
										.executes(this::pregenStart))));
		
		LiteralArgumentBuilder<CommandSourceStack> stopCommand = literal("stop")
				.executes(this::pregenStop);
		
		return literal("pregen")
				.then(statusCommand)
				.then(startCommand)
				.then(stopCommand);
	}
	
	
	private int pregenStatus(CommandContext<CommandSourceStack> c)
	{
		String statusString = this.getPregenManager().getStatusString();
		//noinspection ReplaceNullCheck
		if (statusString != null)
		{
			return this.sendSuccessResponse(c, statusString, false);
		}
		else
		{
			return this.sendSuccessResponse(c, "Pregen is not running", false);
		}
	}
	
	private int pregenStart(CommandContext<CommandSourceStack> c) throws CommandSyntaxException
	{
		this.sendSuccessResponse(c, "Starting pregen. Progress will be in the server console.", true);
		
		ServerLevel level = DimensionArgument.getDimension(c, "dimension");
		ColumnPos origin = ColumnPosArgument.getColumnPos(c, "origin");
		int chunkRadius = getInteger(c, "chunkRadius");
		
		CompletableFuture<Void> future = this.getPregenManager().startPregen(
				ServerLevelWrapper.getWrapper(level),
				new DhBlockPos2D(#if MC_VER >= MC_1_19_2 origin.x(), origin.z() #else origin.x, origin.z #endif),
				chunkRadius
		);
		
		future.whenComplete((result, throwable) -> {
			if (throwable instanceof CancellationException)
			{
				this.sendSuccessResponse(c, "Pregen is cancelled", true);
				return;
			}
			else if (throwable != null)
			{
				this.sendFailureResponse(c, "Pregen failed: " + throwable.getMessage() + "\n Check the logs for more details.");
				return;
			}
			
			this.sendSuccessResponse(c, "Pregen is complete", true);
		});
		
		return 1;
	}
	
	private int pregenStop(CommandContext<CommandSourceStack> c)
	{
		CompletableFuture<Void> runningPregen = this.getPregenManager().getRunningPregen();
		if (runningPregen == null)
		{
			return this.sendFailureResponse(c, "Pregen is not running");
		}
		
		runningPregen.cancel(true);
		return 1;
	}
	#endif
}
