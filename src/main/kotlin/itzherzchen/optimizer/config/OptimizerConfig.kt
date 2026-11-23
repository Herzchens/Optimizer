package itzherzchen.optimizer.config

import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.configuration.file.FileConfiguration

/**
 * Validated configuration for Optimizer plugin.
 */
data class OptimizerConfig(
    val configuredMode: OperatingMode = OperatingMode.OBSERVE,
    val stackedFastRejectEnabled: Boolean = false,
    val blockAccountingFastPathEnabled: Boolean = false,
    val expectedWildToolsSha256: String = DEFAULT_WT_SHA256,
    val expectedSuperiorSkyblockSha256: String = DEFAULT_SSB_SHA256,
    val expectedMinecraftVersion: String = DEFAULT_MC_VERSION,
    val timingSampleRate: Int = DEFAULT_TIMING_SAMPLE_RATE
) {
    companion object {
        const val COMPILED_APPROVED_WT_SHA256 = "dd9ca6393ab5824a4e00def7846697770e4bb546dbf79e8d15957acd968c829e"
        const val COMPILED_APPROVED_SSB_SHA256 = "c7fb750ee60078175384baf4afcbde4f9fa066569bf045f516cefff7e6872168"
        const val COMPILED_APPROVED_MC_VERSION = "1.21.4"
        const val DEFAULT_WT_SHA256 = COMPILED_APPROVED_WT_SHA256
        const val DEFAULT_SSB_SHA256 = COMPILED_APPROVED_SSB_SHA256
        const val DEFAULT_MC_VERSION = COMPILED_APPROVED_MC_VERSION
        const val DEFAULT_TIMING_SAMPLE_RATE = 64

        fun load(config: FileConfiguration): OptimizerConfig {
            val rawMode = config.getString("mode", "OBSERVE")?.uppercase() ?: "OBSERVE"
            val mode = when (rawMode) {
                "ACTIVE" -> OperatingMode.ACTIVE
                "VALIDATED" -> OperatingMode.VALIDATED
                else -> OperatingMode.OBSERVE
            }

            val stackedEnabled = config.getBoolean("modules.stacked-fast-reject", false)
            val accountingEnabled = config.getBoolean("modules.block-accounting-fast-path", false)

            val wtSha = config.getString("pins.wildtools-sha256", DEFAULT_WT_SHA256)?.lowercase() ?: DEFAULT_WT_SHA256
            val ssbSha = config.getString("pins.superiorskyblock-sha256", DEFAULT_SSB_SHA256)?.lowercase() ?: DEFAULT_SSB_SHA256
            val mcVersion = config.getString("pins.minecraft-version", DEFAULT_MC_VERSION) ?: DEFAULT_MC_VERSION
            val sampleRate = config.getInt("timing-sample-rate", DEFAULT_TIMING_SAMPLE_RATE).coerceAtLeast(1)

            return OptimizerConfig(
                configuredMode = mode,
                stackedFastRejectEnabled = stackedEnabled,
                blockAccountingFastPathEnabled = accountingEnabled,
                expectedWildToolsSha256 = wtSha,
                expectedSuperiorSkyblockSha256 = ssbSha,
                expectedMinecraftVersion = mcVersion,
                timingSampleRate = sampleRate
            )
        }
    }
}
