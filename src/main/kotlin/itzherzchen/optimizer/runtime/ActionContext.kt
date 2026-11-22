package itzherzchen.optimizer.runtime

import com.bgsoftware.wildtools.api.objects.tools.CuboidTool
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Context scoped to a single root Cuboid break action.
 *
 * Invariants:
 * - Scoped strictly to the thread executing the root break action.
 * - Created at root BlockBreakEvent LOWEST; popped at MONITOR.
 * - Zero per-block heap allocation on the hot path.
 * - Releases all references to Player and BlockBreakEvent upon close() to avoid memory leaks.
 */
class ActionContext(
    val actionId: Long,
    rootEvent: BlockBreakEvent,
    player: Player,
    tool: CuboidTool,
    val startTimeNanos: Long = 0L
) {
    var rootEvent: BlockBreakEvent? = rootEvent
        private set

    var player: Player? = player
        private set

    var tool: CuboidTool? = tool
        private set

    private val _syntheticBreaksCount = AtomicInteger(0)
    val syntheticBreaksCount: Int
        get() = _syntheticBreaksCount.get()

    @Volatile
    var isConfirmed: Boolean = false

    @Volatile
    var isClosed: Boolean = false
        private set

    val chunkCoverage = ChunkCoverage()

    fun recordSyntheticBreak(event: BlockBreakEvent) {
        if (isClosed) return
        isConfirmed = true
        _syntheticBreaksCount.incrementAndGet()
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        chunkCoverage.reset()
        rootEvent = null
        player = null
        tool = null
    }
}
