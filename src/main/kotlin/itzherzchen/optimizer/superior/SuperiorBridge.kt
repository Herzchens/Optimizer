package itzherzchen.optimizer.superior

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI
import com.bgsoftware.superiorskyblock.api.handlers.GridManager
import com.bgsoftware.superiorskyblock.api.handlers.KeysManager
import com.bgsoftware.superiorskyblock.api.handlers.StackedBlocksManager
import org.bukkit.Bukkit
import java.util.logging.Logger

/**
 * Safe runtime bridge to SuperiorSkyblock2 API and services.
 * Provides fail-closed inspection and handles unexpected absences or exceptions.
 */
class SuperiorBridge(
    private val logger: Logger? = null
) {
    val isPluginEnabled: Boolean
        get() = try {
            val ssb = Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2")
            ssb != null && ssb.isEnabled
        } catch (_: Throwable) {
            false
        }

    fun getStackedBlocks(): StackedBlocksManager? = try {
        SuperiorSkyblockAPI.getStackedBlocks()
    } catch (t: Throwable) {
        logger?.warning("[Optimizer] Failed to obtain StackedBlocksManager: ${t.message}")
        null
    }

    fun getGrid(): GridManager? = try {
        SuperiorSkyblockAPI.getGrid()
    } catch (t: Throwable) {
        logger?.warning("[Optimizer] Failed to obtain GridManager: ${t.message}")
        null
    }

    fun getKeys(): KeysManager? = try {
        SuperiorSkyblockAPI.getKeys()
    } catch (t: Throwable) {
        logger?.warning("[Optimizer] Failed to obtain KeysManager: ${t.message}")
        null
    }
}
