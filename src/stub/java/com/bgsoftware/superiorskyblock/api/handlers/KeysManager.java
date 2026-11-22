package com.bgsoftware.superiorskyblock.api.handlers;

import com.bgsoftware.superiorskyblock.api.key.Key;
import org.bukkit.block.Block;

public interface KeysManager {
    Key getKey(Block block);
}
