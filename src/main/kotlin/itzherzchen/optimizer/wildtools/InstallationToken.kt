package itzherzchen.optimizer.wildtools

import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredListener

/**
 * Verified installation snapshot carrying exact audited identities from ListenerCensus.
 *
 * Eliminates census -> install TOCTOU by ensuring that the exact list and target
 * listener entries audited during validation are the ones present at install time.
 */
data class InstallationToken(
    val targetList: MutableList<RegisteredListener>,
    val stackedBlocksIndex: Int,
    val expectedStackedListener: RegisteredListener,
    val monitorIndex: Int,
    val expectedMonitorListener: RegisteredListener,
    val ssbPlugin: Plugin,
    val ssbClassLoader: ClassLoader,
    val wtPlugin: Plugin? = null,
    val wtClassLoader: ClassLoader? = null,
    val eventsHandler: Any? = null
)
