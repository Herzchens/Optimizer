package com.bgsoftware.superiorskyblock;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.platform.IEventsDispatcher;
import org.bukkit.plugin.java.JavaPlugin;

public class SuperiorSkyblockPlugin extends JavaPlugin implements SuperiorSkyblock {
    private IEventsDispatcher eventsDispatcher = null;

    public static SuperiorSkyblockPlugin getPlugin() {
        return null;
    }

    public IEventsDispatcher getEventsDispatcher() {
        return eventsDispatcher;
    }

    @Override
    public com.bgsoftware.superiorskyblock.api.handlers.GridManager getGrid() {
        return null;
    }

    @Override
    public com.bgsoftware.superiorskyblock.api.handlers.StackedBlocksManager getStackedBlocks() {
        return null;
    }

    @Override
    public com.bgsoftware.superiorskyblock.api.handlers.KeysManager getKeys() {
        return null;
    }
}
