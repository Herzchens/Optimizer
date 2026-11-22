package com.bgsoftware.superiorskyblock.api.handlers;

import org.bukkit.Location;
import org.bukkit.block.Block;

public interface StackedBlocksManager {
    int getStackedBlockAmount(Location location);
    int getStackedBlockAmount(Block block);
}
