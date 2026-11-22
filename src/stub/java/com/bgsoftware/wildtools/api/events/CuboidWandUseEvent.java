package com.bgsoftware.wildtools.api.events;

import com.bgsoftware.wildtools.api.objects.tools.CuboidTool;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.List;

public class CuboidWandUseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean cancelled;
    private final Player player;
    private final CuboidTool tool;
    private final List<Location> blocks;

    public CuboidWandUseEvent() {
        this(null, null, Collections.emptyList());
    }

    public CuboidWandUseEvent(Player player, CuboidTool tool, List<Location> blocks) {
        this.player = player;
        this.tool = tool;
        this.blocks = blocks;
    }

    public Player getPlayer() {
        return player;
    }

    public CuboidTool getTool() {
        return tool;
    }

    public List<Location> getBlocks() {
        return blocks;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
