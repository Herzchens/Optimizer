package com.bgsoftware.superiorskyblock.api;

import com.bgsoftware.superiorskyblock.api.handlers.GridManager;
import com.bgsoftware.superiorskyblock.api.handlers.KeysManager;
import com.bgsoftware.superiorskyblock.api.handlers.StackedBlocksManager;

public interface SuperiorSkyblock {
    GridManager getGrid();
    StackedBlocksManager getStackedBlocks();
    KeysManager getKeys();
}
