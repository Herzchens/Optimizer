package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import itzherzchen.optimizer.runtime.OperatingMode
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginDescriptionFile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeFingerprintTest {

    private fun createPluginJar(
        file: File,
        pluginName: String,
        mainClass: String,
        content: String = "dummy class bytes"
    ): File {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            val entry = ZipEntry("plugin.yml")
            zos.putNextEntry(entry)
            val yaml = "name: $pluginName\nmain: $mainClass\nversion: 1.0.0\n"
            zos.write(yaml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()

            val classEntry = ZipEntry(mainClass.replace('.', '/') + ".class")
            zos.putNextEntry(classEntry)
            zos.write(content.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        return file
    }

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
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `verify local WildTools and SuperiorSkyblock2 hashes match pins if present`() {
        val wtFile = File("libs/WildTools-CUSTOM-2026.1.jar")
        if (wtFile.exists()) {
            val hash = RuntimeFingerprint.computeSha256(wtFile)
            assertEquals(OptimizerConfig.COMPILED_APPROVED_WT_SHA256, hash)
        }

        val ssbFile = File("libs/SuperiorSkyblock2-2026.3-b963.jar")
        if (ssbFile.exists()) {
            val hash = RuntimeFingerprint.computeSha256(ssbFile)
            assertEquals(OptimizerConfig.COMPILED_APPROVED_SSB_SHA256, hash)
        }
    }

    // --- SAFETY PINS CODE POLICY ---

    @Test
    fun `safety pins are code policy and reject operator config overrides`() {
        val rogueConfig = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.COMPILED_APPROVED_MC_VERSION,
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(rogueConfig)
        val result = fingerprint.validate()

        assertFalse(result.isCompatible)
        val policyCheck = result.checks.find { it.name == "Safety Pins Code Policy" }
        assertNotNull(policyCheck)
        assertEquals(InvariantStatus.FAIL, policyCheck!!.status)
        assertTrue(result.failureReasons.any { it.contains("Safety Pins Code Policy") && it.contains("does not match compiled approved policy") })
    }

    @Test
    fun `safety pins code policy passes when config matches compiled constants`() {
        val validConfig = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.COMPILED_APPROVED_MC_VERSION,
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(validConfig)
        val result = fingerprint.validate()

        val policyCheck = result.checks.find { it.name == "Safety Pins Code Policy" }
        assertNotNull(policyCheck)
        assertEquals(InvariantStatus.PASS, policyCheck!!.status)
    }

    // --- ORIGINAL ARTIFACT RESOLVER ---

    @Test
    fun `original artifact resolver resolves remapped Paper jar via plugins directory`(@TempDir tempDir: Path) {
        val pluginsDir = tempDir.resolve("plugins").toFile()
        pluginsDir.mkdirs()
        val remappedDir = tempDir.resolve(".paper-remapped").toFile()
        remappedDir.mkdirs()

        // Original vendor JAR in plugins/
        val originalJar = File(pluginsDir, "WildTools-CUSTOM.jar")
        createPluginJar(originalJar, "WildTools", "com.bgsoftware.wildtools.WildToolsPlugin", "original bytecode")
        val approvedHash = RuntimeFingerprint.computeSha256(originalJar)

        // Remapped JAR in .paper-remapped/ with different content/hash
        val remappedJar = File(remappedDir, "WildTools-remapped.jar")
        createPluginJar(remappedJar, "WildTools", "com.bgsoftware.wildtools.WildToolsPlugin", "remapped Paper bytecode")

        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("WildTools")
        val desc = mock(PluginDescriptionFile::class.java)
        `when`(desc.main).thenReturn("com.bgsoftware.wildtools.WildToolsPlugin")
        `when`(plugin.description).thenReturn(desc)

        // Create resolver pointing to pluginsDir, with runtime CodeSource pointing to remappedJar
        val resolver = OriginalArtifactResolver(
            pluginsFolderProvider = { pluginsDir },
            codeSourceProvider = { remappedJar }
        )

        val result = resolver.resolve(plugin, approvedHash)

        assertTrue(result is ArtifactResolutionResult.Success)
        val success = result as ArtifactResolutionResult.Success
        assertEquals(originalJar.absolutePath, success.file.absolutePath)
        assertEquals("PLUGINS_DIRECTORY_VERIFIED", success.resolutionMethod)
        assertEquals(approvedHash, success.sha256)
    }

    @Test
    fun `original artifact resolver fails closed on ambiguity when multiple matching JARs exist`(@TempDir tempDir: Path) {
        val pluginsDir = tempDir.resolve("plugins").toFile()
        pluginsDir.mkdirs()

        // Two identical JARs in plugins/
        val jar1 = File(pluginsDir, "WildTools-1.jar")
        val jar2 = File(pluginsDir, "WildTools-2.jar")
        createPluginJar(jar1, "WildTools", "com.bgsoftware.wildtools.WildToolsPlugin", "identical bytecode")
        createPluginJar(jar2, "WildTools", "com.bgsoftware.wildtools.WildToolsPlugin", "identical bytecode")
        val approvedHash = RuntimeFingerprint.computeSha256(jar1)

        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("WildTools")
        val desc = mock(PluginDescriptionFile::class.java)
        `when`(desc.main).thenReturn("com.bgsoftware.wildtools.WildToolsPlugin")
        `when`(plugin.description).thenReturn(desc)

        val resolver = OriginalArtifactResolver(pluginsFolderProvider = { pluginsDir })
        val result = resolver.resolve(plugin, approvedHash)

        assertTrue(result is ArtifactResolutionResult.Failure)
        val failure = result as ArtifactResolutionResult.Failure
        assertTrue(failure.reason.contains("Ambiguous original artifact resolution"))
    }

    @Test
    fun `original artifact resolver fails closed on digest mismatch`(@TempDir tempDir: Path) {
        val pluginsDir = tempDir.resolve("plugins").toFile()
        pluginsDir.mkdirs()

        val jar = File(pluginsDir, "WildTools.jar")
        createPluginJar(jar, "WildTools", "com.bgsoftware.wildtools.WildToolsPlugin", "unapproved bytecode")

        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("WildTools")
        val desc = mock(PluginDescriptionFile::class.java)
        `when`(desc.main).thenReturn("com.bgsoftware.wildtools.WildToolsPlugin")
        `when`(plugin.description).thenReturn(desc)

        val resolver = OriginalArtifactResolver(pluginsFolderProvider = { pluginsDir })
        val result = resolver.resolve(plugin, OptimizerConfig.COMPILED_APPROVED_WT_SHA256)

        assertTrue(result is ArtifactResolutionResult.Failure)
        val failure = result as ArtifactResolutionResult.Failure
        assertTrue(failure.reason.contains("No approved original artifact found"))
    }

    @Test
    fun `original artifact resolver ignores jar with matching filename but foreign plugin name`(@TempDir tempDir: Path) {
        val pluginsDir = tempDir.resolve("plugins").toFile()
        pluginsDir.mkdirs()

        // Malicious imposter: filename looks like WildTools.jar, but plugin.yml says MaliciousPlugin
        val jar = File(pluginsDir, "WildTools.jar")
        createPluginJar(jar, "MaliciousPlugin", "com.evil.MaliciousPlugin", "evil bytes")
        val hash = RuntimeFingerprint.computeSha256(jar)

        val plugin = mock(Plugin::class.java)
        `when`(plugin.name).thenReturn("WildTools")
        val desc = mock(PluginDescriptionFile::class.java)
        `when`(desc.main).thenReturn("com.bgsoftware.wildtools.WildToolsPlugin")
        `when`(plugin.description).thenReturn(desc)

        val resolver = OriginalArtifactResolver(pluginsFolderProvider = { pluginsDir })
        val result = resolver.resolve(plugin, hash)

        assertTrue(result is ArtifactResolutionResult.Failure)
        val failure = result as ArtifactResolutionResult.Failure
        assertTrue(failure.reason.contains("No approved original artifact found"))
    }

    // --- MINECRAFT VERSION & SERVER BRAND ---

    @Test
    fun `validate fails when Minecraft version is blank`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.OBSERVE,
            stackedFastRejectEnabled = false,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = "   ",
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(config)
        val result = fingerprint.validate()

        assertFalse(result.isCompatible)
        assertTrue(result.failureReasons.any { it.contains("Minecraft version is blank") || it.contains("Minecraft Version") })
    }

    @Test
    fun `validate fails when plugins are not enabled or absent in unit environment`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.OBSERVE,
            stackedFastRejectEnabled = false,
            blockAccountingFastPathEnabled = false,
            expectedWildToolsSha256 = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.COMPILED_APPROVED_MC_VERSION,
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(config)
        val result = fingerprint.validate()

        assertFalse(result.isCompatible)
        assertTrue(result.failureReasons.any { it.contains("WildTools plugin is not loaded") })
        assertTrue(result.failureReasons.any { it.contains("SuperiorSkyblock2 plugin is not loaded") })
    }

    @Test
    fun `approved Leaf server family brand passes check`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.COMPILED_APPROVED_MC_VERSION,
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(
            config = config,
            serverFamilyChecker = { true to "Approved Leaf server family verified (brand='Leaf', version='git-Leaf-1234')" }
        )
        val result = fingerprint.validate()

        val leafCheck = result.checks.find { it.name == "Server Family (Leaf)" }
        assertNotNull(leafCheck)
        assertEquals(InvariantStatus.PASS, leafCheck!!.status)
        assertTrue(leafCheck.details.contains("Approved Leaf server family verified"))
    }

    @Test
    fun `unexpected Paper or fork server brand fails check and refuses ACTIVE`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.COMPILED_APPROVED_MC_VERSION,
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(
            config = config,
            serverFamilyChecker = { false to "Server implementation 'Paper' (git-Paper-456) is not the approved Leaf server family. ACTIVE mode refused." }
        )
        val result = fingerprint.validate()

        assertFalse(result.isCompatible)
        val leafCheck = result.checks.find { it.name == "Server Family (Leaf)" }
        assertNotNull(leafCheck)
        assertEquals(InvariantStatus.FAIL, leafCheck!!.status)
        assertTrue(result.failureReasons.any { it.contains("not the approved Leaf server family") })
    }

    @Test
    fun `leaf server brand rejects arbitrary forks with leaf in uncontrolled string`() {
        val (isLeaf, _) = RuntimeFingerprint.checkLeafServerFamily()
        assertFalse(isLeaf)
    }

    @Test
    fun `top level fingerprint exception fails closed safely`() {
        val config = OptimizerConfig(
            configuredMode = OperatingMode.ACTIVE,
            stackedFastRejectEnabled = true,
            blockAccountingFastPathEnabled = true,
            expectedWildToolsSha256 = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
            expectedSuperiorSkyblockSha256 = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
            expectedMinecraftVersion = OptimizerConfig.COMPILED_APPROVED_MC_VERSION,
            timingSampleRate = 64
        )

        val fingerprint = RuntimeFingerprint(
            config = config,
            serverFamilyChecker = { throw RuntimeException("Catastrophic server family probe failure") }
        )

        val result = fingerprint.validate()
        assertFalse(result.isCompatible)
        assertTrue(result.failureReasons.any { it.contains("Top-level validation error") })
    }
}
