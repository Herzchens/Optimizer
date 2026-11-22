package itzherzchen.optimizer

import org.bukkit.plugin.java.JavaPlugin

/**
 * Optimizer — Fail-Closed HotPathGuard Plugin for Leaf / Paper 1.21.4.
 *
 * Author: itzherzchen
 * Package Root: itzherzchen.optimizer
 */
class OptimizerPlugin : JavaPlugin() {

    override fun onEnable() {
        logger.info("Optimizer plugin bootstrapped successfully.")
    }

    override fun onDisable() {
        logger.info("Optimizer plugin disabled.")
    }
}
