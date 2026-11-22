package com.bgsoftware.superiorskyblock.api;

import com.bgsoftware.superiorskyblock.api.handlers.GridManager;
import com.bgsoftware.superiorskyblock.api.handlers.KeysManager;
import com.bgsoftware.superiorskyblock.api.handlers.StackedBlocksManager;

public final class SuperiorSkyblockAPI {
    private static SuperiorSkyblock plugin;

    private SuperiorSkyblockAPI() {}

    public static SuperiorSkyblock getSuperiorSkyblock() {
        return plugin;
    }

    public static void setPluginInstance(SuperiorSkyblock pluginInstance) {
        plugin = pluginInstance;
    }

    public static GridManager getGrid() {
        return plugin == null ? null : plugin.getGrid();
    }

    public static StackedBlocksManager getStackedBlocks() {
        return plugin == null ? null : plugin.getStackedBlocks();
    }

    public static KeysManager getKeys() {
        return plugin == null ? null : plugin.getKeys();
    }
}

