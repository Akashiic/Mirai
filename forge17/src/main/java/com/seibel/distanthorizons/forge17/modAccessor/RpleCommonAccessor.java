package com.seibel.distanthorizons.forge17.modAccessor;

import com.falsepattern.rple.api.common.ServerColorHelper;
import com.falsepattern.rple.api.common.block.RPLEBlock;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IRpleCommonAccessor;
import net.minecraft.block.Block;

/** Light-emission queries usable on a dedicated server, without GL or client classes. */
public class RpleCommonAccessor implements IRpleCommonAccessor
{
    @Override
    public String getModName() { return "rple"; }

    @Override
    public int getColor(Block block, int meta)
    {
        short color = RPLEBlock.of(block).rple$getBrightnessColor(meta);
        return ServerColorHelper.lightValueFromRGB16(color);
    }
}
