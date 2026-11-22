package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import org.bukkit.Bukkit
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Field
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.logging.Logger

enum class InvariantStatus {
    PASS,
    FAIL,
    SKIPPED
}

data class InvariantCheck(
    val name: String,
    val status: InvariantStatus,
    val details: String
)

data class FingerprintResult(
    val isCompatible: Boolean,
    val checks: List<InvariantCheck>,
    val failureReasons: List<String>
)

/**
 * Strict fail-closed runtime fingerprint validator.
 * Asserts Minecraft version, binary SHA-256 hashes, classloader integrity,
 * and reflective bytecode structure before any optimization is permitted.
 */
class RuntimeFingerprint(
    private val config: OptimizerConfig,
    private val logger: Logger? = null
) {
    fun validate(): FingerprintResult {
        val checks = mutableListOf<InvariantCheck>()
        val failures = mutableListOf<String>()

        fun record(name: String, passed: Boolean, details: String) {
            val status = if (passed) InvariantStatus.PASS else InvariantStatus.FAIL
            checks.add(InvariantCheck(name, status, details))
            if (!passed) {
                failures.add("$name: $details")
                logger?.warning("[Optimizer] Fingerprint check FAILED - $name: $details")
            } else {
                logger?.fine("[Optimizer] Fingerprint check PASSED - $name: $details")
            }
        }

        // 1. Minecraft version check
        if (config.expectedMinecraftVersion.isBlank()) {
            record("Minecraft Version", false, "Expected Minecraft version is blank in configuration")
        } else {
            val bukkitVersion = try { Bukkit.getBukkitVersion() } catch (_: Exception) { "" }
            val serverVersion = try { Bukkit.getVersion() } catch (_: Exception) { "" }
            val mcMatch = bukkitVersion.contains(config.expectedMinecraftVersion) ||
                    serverVersion.contains(config.expectedMinecraftVersion)
            record(
                "Minecraft Version",
                mcMatch,
                "Expected '${config.expectedMinecraftVersion}', Bukkit version: '$bukkitVersion', Server: '$serverVersion'"
            )
        }

        // 2. WildTools plugin presence and classloader check
        val wtPlugin = try { Bukkit.getPluginManager()?.getPlugin("WildTools") } catch (_: Exception) { null }
        if (wtPlugin == null || !wtPlugin.isEnabled) {
            record("WildTools Enabled", false, "WildTools plugin is not loaded or not enabled")
        } else {
            record("WildTools Enabled", true, "WildTools version ${wtPlugin.description.version} is enabled")

            // Check classloader
            val wtLoader = wtPlugin.javaClass.classLoader
            val isStandardLoader = wtLoader != null && wtLoader.javaClass.name.contains("PluginClassLoader")
            record(
                "WildTools ClassLoader",
                isStandardLoader,
                "ClassLoader: ${wtLoader?.javaClass?.name ?: "null"}"
            )

            // WildTools JAR SHA-256
            validateJarHash(
                plugin = wtPlugin,
                expectedHash = config.expectedWildToolsSha256,
                pluginName = "WildTools",
                record = ::record
            )

            // WildTools Reflective Structure
            validateWildToolsStructure(wtPlugin, ::record)
        }

        // 3. SuperiorSkyblock2 plugin presence and classloader check
        val ssbPlugin = try { Bukkit.getPluginManager()?.getPlugin("SuperiorSkyblock2") } catch (_: Exception) { null }

        if (ssbPlugin == null || !ssbPlugin.isEnabled) {
            record("SuperiorSkyblock2 Enabled", false, "SuperiorSkyblock2 plugin is not loaded or not enabled")
        } else {
            record("SuperiorSkyblock2 Enabled", true, "SuperiorSkyblock2 version ${ssbPlugin.description.version} is enabled")

            // Check classloader
            val ssbLoader = ssbPlugin.javaClass.classLoader
            val isStandardLoader = ssbLoader != null && ssbLoader.javaClass.name.contains("PluginClassLoader")
            record(
                "SuperiorSkyblock2 ClassLoader",
                isStandardLoader,
                "ClassLoader: ${ssbLoader?.javaClass?.name ?: "null"}"
            )

            // SuperiorSkyblock2 JAR SHA-256
            validateJarHash(
                plugin = ssbPlugin,
                expectedHash = config.expectedSuperiorSkyblockSha256,
                pluginName = "SuperiorSkyblock2",
                record = ::record
            )

            // SuperiorSkyblock2 Reflective Structure & EventsDispatcher check
            validateSuperiorSkyblockStructure(ssbPlugin, ::record)
        }

        val allPassed = failures.isEmpty()
        return FingerprintResult(allPassed, checks, failures)
    }

    private fun validateJarHash(
        plugin: Plugin,
        expectedHash: String,
        pluginName: String,
        record: (String, Boolean, String) -> Unit
    ) {
        val codeSource = plugin.javaClass.protectionDomain?.codeSource
        if (codeSource == null) {
            record("$pluginName JAR Location", false, "ProtectionDomain codeSource is null")
            return
        }

        val location = codeSource.location
        val file: File = try {
            File(location.toURI())
        } catch (_: Exception) {
            val decoded = URLDecoder.decode(location.path, StandardCharsets.UTF_8.name())
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val cleanPath = if (isWindows && decoded.matches(Regex("^/[a-zA-Z]:/.*"))) {
                decoded.substring(1)
            } else {
                decoded
            }
            File(cleanPath)
        }

        if (!file.exists() || !file.isFile || !file.canRead()) {
            record("$pluginName JAR Readable", false, "Cannot read file at ${file.absolutePath}")
            return
        }

        val computedHash = computeSha256(file)
        val matches = computedHash.equals(expectedHash, ignoreCase = true)
        record(
            "$pluginName SHA-256 Hash",
            matches,
            "Computed: $computedHash, Expected: $expectedHash"
        )
    }

    private fun validateWildToolsStructure(
        plugin: Plugin,
        record: (String, Boolean, String) -> Unit
    ) {
        try {
            val eventsMethod = plugin.javaClass.getMethod("getEvents")
            val eventsHandler = eventsMethod.invoke(plugin)
            if (eventsHandler == null) {
                record("WildTools EventsHandler", false, "getEvents() returned null")
                return
            }
            record("WildTools EventsHandler", true, "Retrieved instance ${eventsHandler.javaClass.name}")

            val field = findField(eventsHandler.javaClass, "claimingPluginsBreakMethods")
            if (field == null) {
                record("WildTools claimingPluginsBreakMethods", false, "Field not found on ${eventsHandler.javaClass.name}")
                return
            }
            field.isAccessible = true
            val list = field.get(eventsHandler)
            val isList = list is List<*>
            record(
                "WildTools claimingPluginsBreakMethods Type",
                isList,
                "Field found: ${field.type.name}, value is List: $isList"
            )

            // Validate callBreakEvent signature
            val breakMethod = eventsHandler.javaClass.getMethod("callBreakEvent", BlockBreakEvent::class.java, Boolean::class.javaPrimitiveType)
            record("WildTools callBreakEvent Signature", true, "Method found: $breakMethod")
        } catch (e: Exception) {
            record("WildTools Structure Reflection", false, "Exception during reflection: ${e.message}")
        }
    }

    private fun validateSuperiorSkyblockStructure(
        plugin: Plugin,
        record: (String, Boolean, String) -> Unit
    ) {
        try {
            // Check eventsDispatcher field is null
            val dispatcherField = findField(plugin.javaClass, "eventsDispatcher")
            if (dispatcherField == null) {
                record("SSB eventsDispatcher Field", false, "Field not found on ${plugin.javaClass.name}")
            } else {
                dispatcherField.isAccessible = true
                val dispatcherValue = dispatcherField.get(plugin)
                record(
                    "SSB eventsDispatcher Null Invariant",
                    dispatcherValue == null,
                    "Field value: $dispatcherValue (expected null)"
                )
            }

            // Check getEventsDispatcher() method exists and returns null
            try {
                val getDispatcherMethod = plugin.javaClass.getMethod("getEventsDispatcher")
                val dispatcherResult = getDispatcherMethod.invoke(plugin)
                record(
                    "SSB getEventsDispatcher() Null Invariant",
                    dispatcherResult == null,
                    "Method returned: $dispatcherResult (expected null)"
                )
            } catch (e: Exception) {
                record("SSB getEventsDispatcher() Method", false, "Exception: ${e.message}")
            }

            // Check getGameEventsDispatcher() method
            val gameDispatcherField = findField(plugin.javaClass, "gameEventsDispatcher")
            record(
                "SSB gameEventsDispatcher Field",
                gameDispatcherField != null,
                "Internal gameEventsDispatcher field present: ${gameDispatcherField != null}"
            )

            // Check getStackedBlocks() method
            val stackedBlocksMethod = plugin.javaClass.getMethod("getStackedBlocks")
            val stackedBlocks = stackedBlocksMethod.invoke(plugin)
            record("SSB getStackedBlocks()", stackedBlocks != null, "Returned: ${stackedBlocks?.javaClass?.name}")

            // Check getKeys() method
            val keysMethod = plugin.javaClass.getMethod("getKeys")
            val keys = keysMethod.invoke(plugin)
            record("SSB getKeys()", keys != null, "Returned: ${keys?.javaClass?.name}")

            // Check getGrid() method
            val gridMethod = plugin.javaClass.getMethod("getGrid")
            val grid = gridMethod.invoke(plugin)
            record("SSB getGrid()", grid != null, "Returned: ${grid?.javaClass?.name}")

        } catch (e: Exception) {
            record("SSB Structure Reflection", false, "Exception during reflection: ${e.message}")
        }
    }

    private fun findField(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    companion object {
        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
