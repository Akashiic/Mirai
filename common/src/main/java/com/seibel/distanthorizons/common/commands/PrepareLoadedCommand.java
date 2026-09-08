package com.seibel.distanthorizons.common.commands;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.util.LegacyLoadedTerrainPreparation;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.common.DimensionManager;

/** The name deliberately distinguishes resident capture from disk import or world generation. */
public final class PrepareLoadedCommand extends AbstractDhCommand
{
    public void execute(ICommandSender sender, String[] args)
    {
        if (args.length < 2)
        {
            usage(sender);
            return;
        }
        try
        {
            switch (args[1])
            {
                case "status":
                    if (args.length != 2) { usage(sender); return; }
                    sendMessage(sender, LegacyLoadedTerrainPreparation.INSTANCE.status());
                    return;
                case "stop":
                    if (args.length != 2) { usage(sender); return; }
                    sendMessage(sender, LegacyLoadedTerrainPreparation.INSTANCE.stop());
                    return;
                case "loaded":
                    if (args.length != 6) { usage(sender); return; }
                    int dimension = Integer.parseInt(args[2]);
                    int blockX = Integer.parseInt(args[3]);
                    int blockZ = Integer.parseInt(args[4]);
                    int radius = Integer.parseInt(args[5]);
                    sendMessage(sender, LegacyLoadedTerrainPreparation.INSTANCE.start(
                        DimensionManager.getWorld(dimension), blockX, blockZ, radius));
                    return;
                default:
                    usage(sender);
            }
        }
        catch (NumberFormatException invalidNumber)
        {
            sendMessage(sender, "Dimension, block coordinates and chunk radius must be whole numbers.");
        }
        catch (IllegalArgumentException | IllegalStateException rejected)
        {
            sendMessage(sender, "Loaded-terrain scan not started: " + rejected.getMessage());
        }
    }

    private static void usage(ICommandSender sender)
    {
        sendMessage(sender, "Usage: /dh prepare loaded <dimensionId> <blockX> <blockZ> <chunkRadius 0-64> | /dh prepare <status|stop>");
        sendMessage(sender, "Only already-loaded terrain is submitted to DH. Unloaded chunks are skipped; this is not disk import or world generation.");
    }
}
#endif
