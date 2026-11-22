package com.bgsoftware.superiorskyblock.api.handlers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.bukkit.Location;
import org.bukkit.World;

public interface GridManager {
    boolean isIslandsWorld(World world);
    Island getIslandAt(Location location);
}
