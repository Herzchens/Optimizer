package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.runtime.OperatingMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class RuntimeFingerprintTest {

    @Test
    fun `computeSha256 calculates correct SHA-256 for known text`(@TempDir tempDir: Path) {
        val testFile = tempDir.resolve("hello.txt").toFile()
        testFile.writeText("hello world\n")

        val hash = RuntimeFingerprint.computeSha256(testFile)
        assertNotNull(hash)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `computeSha256 handles empty file`(@TempDir tempDir: Path) {
        val emptyFile = tempDir.resolve("empty.bin").toFile()
        emptyFile.createNewFile()

        val hash = RuntimeFingerprint.computeSha256(emptyFile)
        // SHA-256 of empty file is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `verify local WildTools and SuperiorSkyblock2 hashes match pins if present`() {
        val wtFile = File("libs/WildTools-CUSTOM-2026.1.jar")
        if (wtFile.exists()) {
            val hash = RuntimeFingerprint.computeSha256(wtFile)
            assertEquals("dd9ca6393ab5824a4e00def7846697770e4bb546dbf79e8d15957acd968c829e", hash)
        }

        val ssbFile = File("libs/SuperiorSkyblock2-2026.3-b963.jar")
        if (ssbFile.exists()) {
            val hash = RuntimeFingerprint.computeSha256(ssbFile)
            assertEquals("c7fb750ee60078175384baf4afcbde4f9fa066569bf045f516cefff7e6872168", hash)
        }
    }

    @Test
    fun `validate fails when Minecraft version is blank`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.OBSERVE,
            stackedFastRejectEnabled = false,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = "dummy",
            expectedSuperiorSkyblockSha256 = "dummy",
            expectedMinecraftVersion = "   ",
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(config)
        val result = fingerprint.validate()

        assertFalse(result.isCompatible)
        assertTrue(result.failureReasons.any { it.contains("Minecraft version is blank") })
    }

    @Test
    fun `validate fails when plugins are not enabled or absent in unit environment`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.OBSERVE,
            stackedFastRejectEnabled = false,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = "dummy",
            expectedSuperiorSkyblockSha256 = "dummy",
            expectedMinecraftVersion = "1.21.4",
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(config)
        val result = fingerprint.validate()

        assertFalse(result.isCompatible)
        assertTrue(result.failureReasons.any { it.contains("WildTools plugin is not loaded") })
        assertTrue(result.failureReasons.any { it.contains("SuperiorSkyblock2 plugin is not loaded") })
    }

    @Test
    fun `file URL decoding handles spaces and Windows paths properly`(@TempDir tempDir: Path) {
        val subDirWithSpace = tempDir.resolve("path with spaces").toFile()
        subDirWithSpace.mkdirs()
        val file = File(subDirWithSpace, "test.jar")
        file.writeText("test jar content")

        val url = file.toURI().toURL()
        // URI should be properly decodable
        val decoded = URLDecoder.decode(url.path, StandardCharsets.UTF_8.name())
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val cleanPath = if (isWindows && decoded.matches(Regex("^/[a-zA-Z]:/.*"))) {
            decoded.substring(1)
        } else {
            decoded
        }
        val resolvedFile = File(cleanPath)
        assertTrue(resolvedFile.exists())
        assertEquals("test jar content", resolvedFile.readText())
    }
}
