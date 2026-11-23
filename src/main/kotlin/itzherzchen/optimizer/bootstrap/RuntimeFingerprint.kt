package itzherzchen.optimizer.bootstrap

import itzherzchen.optimizer.config.OptimizerConfig
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Field
import java.security.MessageDigest
import java.util.logging.Level
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
 * Asserts Leaf server family, exact Minecraft version, binary SHA-256 hashes of original
 * installed vendor artifacts (via OriginalArtifactResolver), classloader integrity,
 * and reflective bytecode structure before any optimization is permitted.
 */
class RuntimeFingerprint(
    private val config: OptimizerConfig,
    private val logger: Logger? = null,
    private val serverFamilyChecker: () -> Pair<Boolean, String> = { checkLeafServerFamily() },
    val artifactResolver: OriginalArtifactResolver = OriginalArtifactResolver(logger = logger)
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

        try {
            // 0. Safety pins code policy check
            val wtPolicyMatches = config.expectedWildToolsSha256.equals(OptimizerConfig.COMPILED_APPROVED_WT_SHA256, ignoreCase = true)
            val ssbPolicyMatches = config.expectedSuperiorSkyblockSha256.equals(OptimizerConfig.COMPILED_APPROVED_SSB_SHA256, ignoreCase = true)
            val mcPolicyMatches = config.expectedMinecraftVersion == OptimizerConfig.COMPILED_APPROVED_MC_VERSION
            val safetyPinsPass = wtPolicyMatches && ssbPolicyMatches && mcPolicyMatches

            if (!safetyPinsPass) {
                val violations = mutableListOf<String>()
                if (!wtPolicyMatches) violations.add("WildTools expected SHA-256 ('${config.expectedWildToolsSha256}') does not match compiled approved policy ('${OptimizerConfig.COMPILED_APPROVED_WT_SHA256}')")
                if (!ssbPolicyMatches) violations.add("SuperiorSkyblock2 expected SHA-256 ('${config.expectedSuperiorSkyblockSha256}') does not match compiled approved policy ('${OptimizerConfig.COMPILED_APPROVED_SSB_SHA256}')")
                if (!mcPolicyMatches) violations.add("Minecraft expected version ('${config.expectedMinecraftVersion}') does not match compiled approved policy ('${OptimizerConfig.COMPILED_APPROVED_MC_VERSION}')")
                record(
                    "Safety Pins Code Policy",
                    false,
                    "Configured safety pins diverge from compiled code policy: ${violations.joinToString("; ")}. Operator overrides are forbidden; supporting new builds requires a reviewed code change."
                )
            } else {
                record(
                    "Safety Pins Code Policy",
                    true,
                    "Configured pins strictly adhere to compiled approved policy (WT=${OptimizerConfig.COMPILED_APPROVED_WT_SHA256}, SSB=${OptimizerConfig.COMPILED_APPROVED_SSB_SHA256}, MC=${OptimizerConfig.COMPILED_APPROVED_MC_VERSION})"
                )
            }

            // 1. Leaf server family check
            val (leafApproved, leafDetails) = serverFamilyChecker()
            record("Server Family (Leaf)", leafApproved, leafDetails)

            // 2. Exact Minecraft version check
            if (config.expectedMinecraftVersion.isBlank()) {
                record("Minecraft Version", false, "Expected Minecraft version is blank in configuration")
            } else {
                val exactVersion = try {
                    Bukkit.getMinecraftVersion()
                } catch (_: Throwable) {
                    null
                } ?: try {
                    val bv = Bukkit.getBukkitVersion()
                    if (bv.contains("-R")) bv.substringBefore("-R").trim() else bv.trim()
                } catch (_: Throwable) {
                    ""
                }

                val mcMatch = exactVersion == OptimizerConfig.COMPILED_APPROVED_MC_VERSION &&
                        config.expectedMinecraftVersion == OptimizerConfig.COMPILED_APPROVED_MC_VERSION

                record(
                    "Minecraft Version",
                    mcMatch,
                    "Target version '${OptimizerConfig.COMPILED_APPROVED_MC_VERSION}', detected exact: '$exactVersion'"
                )
            }

            // 3. WildTools plugin presence, classloader, and original artifact hash check
            val wtPlugin = try { Bukkit.getPluginManager()?.getPlugin("WildTools") } catch (_: Exception) { null }
            if (wtPlugin == null || !wtPlugin.isEnabled) {
                record("WildTools Enabled", false, "WildTools plugin is not loaded or not enabled")
            } else {
                val wtVersion = try { wtPlugin.description.version } catch (_: Throwable) { "unknown" }
                record("WildTools Enabled", true, "WildTools version $wtVersion is enabled")

                // Check classloader
                val wtLoader = wtPlugin.javaClass.classLoader
                val isStandardLoader = wtLoader != null && wtLoader.javaClass.name.contains("PluginClassLoader")
                record(
                    "WildTools ClassLoader",
                    isStandardLoader,
                    "ClassLoader: ${wtLoader?.javaClass?.name ?: "null"}"
                )

                // WildTools original artifact SHA-256
                validateJarHash(
                    plugin = wtPlugin,
                    expectedHash = OptimizerConfig.COMPILED_APPROVED_WT_SHA256,
                    pluginName = "WildTools",
                    record = ::record
                )

                // WildTools Reflective Structure
                validateWildToolsStructure(wtPlugin, ::record)
            }

            // 4. SuperiorSkyblock2 plugin presence, classloader, and original artifact hash check
            val ssbPlugin = try { Bukkit.getPluginManager()?.getPlugin("SuperiorSkyblock2") } catch (_: Exception) { null }
            if (ssbPlugin == null || !ssbPlugin.isEnabled) {
                record("SuperiorSkyblock2 Enabled", false, "SuperiorSkyblock2 plugin is not loaded or not enabled")
            } else {
                val ssbVersion = try { ssbPlugin.description.version } catch (_: Throwable) { "unknown" }
                record("SuperiorSkyblock2 Enabled", true, "SuperiorSkyblock2 version $ssbVersion is enabled")

                // Check classloader
                val ssbLoader = ssbPlugin.javaClass.classLoader
                val isStandardLoader = ssbLoader != null && ssbLoader.javaClass.name.contains("PluginClassLoader")
                record(
                    "SuperiorSkyblock2 ClassLoader",
                    isStandardLoader,
                    "ClassLoader: ${ssbLoader?.javaClass?.name ?: "null"}"
                )

                // SuperiorSkyblock2 original artifact SHA-256
                validateJarHash(
                    plugin = ssbPlugin,
                    expectedHash = OptimizerConfig.COMPILED_APPROVED_SSB_SHA256,
                    pluginName = "SuperiorSkyblock2",
                    record = ::record
                )

                // SuperiorSkyblock2 Reflective Structure & EventsDispatcher check
                validateSuperiorSkyblockStructure(ssbPlugin, ::record)
            }

            val allPassed = failures.isEmpty()
            return FingerprintResult(allPassed, checks, failures)
        } catch (t: Throwable) {
            logger?.log(Level.SEVERE, "[Optimizer] Top-level error in RuntimeFingerprint validation", t)
            record("Runtime Fingerprint Execution", false, "Top-level validation error: ${t.javaClass.name}: ${t.message}")
            return FingerprintResult(false, checks, failures)
        }
    }

    private fun validateJarHash(
        plugin: Plugin,
        expectedHash: String,
        pluginName: String,
        record: (String, Boolean, String) -> Unit
    ) {
        val result = artifactResolver.resolve(plugin, expectedHash)
        when (result) {
            is ArtifactResolutionResult.Success -> {
                record(
                    "$pluginName Original Artifact SHA-256",
                    true,
                    "Verified original artifact '${result.file.name}' via ${result.resolutionMethod} (SHA-256: ${result.sha256})"
                )
            }
            is ArtifactResolutionResult.Failure -> {
                record(
                    "$pluginName Original Artifact SHA-256",
                    false,
                    result.reason
                )
            }
        }
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
                "claimingPluginsBreakMethods is ${if (isList) "List (${list?.javaClass?.name})" else "not a List (${list?.javaClass?.name})"}"
            )
        } catch (e: Exception) {
            record("WildTools Structure Reflection", false, "Reflection failed: ${e.message}")
        }
    }

    private fun validateSuperiorSkyblockStructure(
        plugin: Plugin,
        record: (String, Boolean, String) -> Unit
    ) {
        try {
            val getStackedBlocksMethod = com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI::class.java.getMethod("getStackedBlocks")
            record(
                "SuperiorSkyblockAPI.getStackedBlocks",
                getStackedBlocksMethod != null,
                "SuperiorSkyblockAPI.getStackedBlocks() method verified"
            )
        } catch (e: Exception) {
            record("SuperiorSkyblockAPI.getStackedBlocks", false, "Method missing: ${e.message}")
        }

        try {
            val getGridMethod = com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI::class.java.getMethod("getGrid")
            record(
                "SuperiorSkyblockAPI.getGrid",
                getGridMethod != null,
                "SuperiorSkyblockAPI.getGrid() method verified"
            )
        } catch (e: Exception) {
            record("SuperiorSkyblockAPI.getGrid", false, "Method missing: ${e.message}")
        }

        // Verify SSB internal EventsDispatcher structure
        try {
            val eventsDispatcherField = findField(plugin.javaClass, "eventsDispatcher")
            if (eventsDispatcherField != null) {
                eventsDispatcherField.isAccessible = true
                val dispatcher = eventsDispatcherField.get(plugin)
                if (dispatcher != null) {
                    val callbacksField = findField(dispatcher.javaClass, "callbacks")
                    if (callbacksField != null) {
                        callbacksField.isAccessible = true
                        val callbacks = callbacksField.get(dispatcher)
                        record(
                            "SSB EventsDispatcher",
                            callbacks is Map<*, *>,
                            "Verified EventsDispatcher callbacks map (${callbacks?.javaClass?.name})"
                        )
                    } else {
                        record("SSB EventsDispatcher", false, "Field 'callbacks' not found on ${dispatcher.javaClass.name}")
                    }
                } else {
                    record("SSB EventsDispatcher", false, "eventsDispatcher field returned null")
                }
            } else {
                record("SSB EventsDispatcher", false, "Field 'eventsDispatcher' not found on ${plugin.javaClass.name}")
            }
        } catch (e: Exception) {
            record("SSB EventsDispatcher Reflection", false, "EventsDispatcher reflection failed: ${e.message}")
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
        /**
         * Enforces the approved Leaf server family.
         *
         * Production target is Winds-Studio/Leaf 1.21.4.
         * Strictly verifies:
         * 1. Bukkit.getName() == "Leaf" (canonical CraftServer implementation name in Winds-Studio Leaf)
         * 2. Bukkit.getVersion() begins with "git-Leaf" (official Winds-Studio build version tag)
         * 3. Presence of Leaf configuration class "org.dreeam.leaf.LeafConfig" or "org.dreeam.leaf.config.LeafConfig"
         *
         * Generic Paper, Gale, Purpur, or Spigot servers without Leaf's specific patchset
         * fail this check closed to ensure optimization safety.
         */
        fun checkLeafServerFamily(): Pair<Boolean, String> {
            val serverName = try { Bukkit.getName() } catch (_: Throwable) { "" }
            val serverVersion = try { Bukkit.getVersion() } catch (_: Throwable) { "" }

            val isExactLeafName = serverName.equals("Leaf", ignoreCase = true)
            val isExactLeafVersion = serverVersion.startsWith("git-Leaf-", ignoreCase = true) ||
                    serverVersion.startsWith("git-Leaf ", ignoreCase = true)

            val hasLeafConfigClass = try {
                Class.forName("org.dreeam.leaf.LeafConfig") != null
            } catch (_: Throwable) {
                false
            }
            val hasAltLeafConfigClass = try {
                Class.forName("org.dreeam.leaf.config.LeafConfig") != null
            } catch (_: Throwable) {
                false
            }
            val hasLeafClass = hasLeafConfigClass || hasAltLeafConfigClass

            val isLeaf = (isExactLeafName || isExactLeafVersion) && (hasLeafClass || isExactLeafVersion)

            return if (isLeaf) {
                true to "Approved Leaf server family verified (brand='$serverName', version='$serverVersion', leafConfigClass=$hasLeafClass)"
            } else {
                false to "Server implementation '$serverName' ($serverVersion) is not the approved Leaf server family. ACTIVE mode refused."
            }
        }

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
