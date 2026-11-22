package itzherzchen.optimizer.config

import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.StringReader

class OptimizerConfigTest {

    @Test
    fun `load default config from empty YAML`() {
        val yaml = YamlConfiguration()
        val config = OptimizerConfig.load(yaml)

        assertEquals(OperatingMode.OBSERVE, config.configuredMode)
        assertFalse(config.stackedFastRejectEnabled)
        assertFalse(config.blockAccountingFastPathEnabled)
        assertEquals(OptimizerConfig.DEFAULT_WT_SHA256, config.expectedWildToolsSha256)
        assertEquals(OptimizerConfig.DEFAULT_SSB_SHA256, config.expectedSuperiorSkyblockSha256)
        assertEquals("1.21.4", config.expectedMinecraftVersion)
        assertEquals(64, config.timingSampleRate)
    }

    @Test
    fun `load custom config with ACTIVE mode and module flags`() {
        val rawYaml = """
            mode: ACTIVE
            modules:
              stacked-fast-reject: true
              block-accounting-fast-path: true
            pins:
              wildtools-sha256: "ABCDEF123456"
              superiorskyblock-sha256: "7890ABCDEF"
              minecraft-version: "1.21.4"
            timing-sample-rate: 128
        """.trimIndent()

        val yaml = YamlConfiguration.loadConfiguration(StringReader(rawYaml))
        val config = OptimizerConfig.load(yaml)

        assertEquals(OperatingMode.ACTIVE, config.configuredMode)
        assertTrue(config.stackedFastRejectEnabled)
        assertTrue(config.blockAccountingFastPathEnabled)
        assertEquals("abcdef123456", config.expectedWildToolsSha256)
        assertEquals("7890abcdef", config.expectedSuperiorSkyblockSha256)
        assertEquals("1.21.4", config.expectedMinecraftVersion)
        assertEquals(128, config.timingSampleRate)
    }

    @Test
    fun `unknown mode falls back to OBSERVE`() {
        val rawYaml = "mode: UNKNOWN_CORRUPT_MODE"
        val yaml = YamlConfiguration.loadConfiguration(StringReader(rawYaml))
        val config = OptimizerConfig.load(yaml)

        assertEquals(OperatingMode.OBSERVE, config.configuredMode)
    }

    @Test
    fun `timing sample rate coerced to minimum 1`() {
        val rawYaml = "timing-sample-rate: -5"
        val yaml = YamlConfiguration.loadConfiguration(StringReader(rawYaml))
        val config = OptimizerConfig.load(yaml)

        assertEquals(1, config.timingSampleRate)
    }
}
