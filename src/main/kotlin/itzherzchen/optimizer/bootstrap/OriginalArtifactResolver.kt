package itzherzchen.optimizer.bootstrap

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile
import java.util.logging.Logger

sealed class ArtifactResolutionResult {
    data class Success(
        val file: File,
        val sha256: String,
        val resolutionMethod: String
    ) : ArtifactResolutionResult()

    data class Failure(
        val reason: String
    ) : ArtifactResolutionResult()
}

/**
 * Fail-closed resolver for original installed vendor plugin artifacts.
 *
 * Paper/Leaf 1.20.5+ remaps/deobfuscates Bukkit plugins at runtime, storing
 * remapped artifacts in `.paper-remapped` whose hashes differ from the original vendor JAR.
 *
 * Invariants:
 * - Direct CodeSource is accepted only if its hash matches the approved pin.
 * - Does not simply pin a server-generated remapped hash.
 * - Never recursively trusts `.paper-remapped`.
 * - Locates original installed JAR in the plugins folder using plugin metadata (`name`, `main`)
 *   and exact expected SHA-256 digest.
 * - Zero matches -> fail closed.
 * - Ambiguous matches (>1 matching candidate) -> fail closed.
 * - Filename alone is never trusted.
 */
class OriginalArtifactResolver(
    private val pluginsFolderProvider: () -> File = {
        try {
            Bukkit.getPluginsFolder()
        } catch (_: Throwable) {
            File("plugins")
        }
    },
    private val codeSourceProvider: (Plugin) -> File? = { getCodeSourceFile(it) },
    private val logger: Logger? = null
) {
    fun resolve(
        plugin: Plugin,
        expectedSha256: String
    ): ArtifactResolutionResult {
        val codeSourceFile = codeSourceProvider(plugin)

        // 1. Direct CodeSource verification
        if (codeSourceFile != null && codeSourceFile.exists() && codeSourceFile.isFile) {
            val codeSourceHash = try {
                RuntimeFingerprint.computeSha256(codeSourceFile)
            } catch (t: Throwable) {
                logger?.warning("[Optimizer] Failed to compute CodeSource hash for ${plugin.name}: ${t.message}")
                ""
            }

            if (codeSourceHash.equals(expectedSha256, ignoreCase = true)) {
                return ArtifactResolutionResult.Success(
                    file = codeSourceFile,
                    sha256 = codeSourceHash,
                    resolutionMethod = "DIRECT_CODESOURCE"
                )
            } else if (codeSourceHash.isNotEmpty()) {
                logger?.info(
                    "[Optimizer] Runtime CodeSource for ${plugin.name} at ${codeSourceFile.absolutePath} has digest $codeSourceHash != expected $expectedSha256 (possibly remapped by Paper loader). Searching plugins directory for original installed artifact."
                )
            }
        }

        // 2. Scan plugins directory (non-recursively, never trusting .paper-remapped)
        val pluginsDir = try {
            pluginsFolderProvider()
        } catch (t: Throwable) {
            return ArtifactResolutionResult.Failure(
                "Failed to access plugins directory: ${t.message}"
            )
        }

        if (!pluginsDir.exists() || !pluginsDir.isDirectory) {
            return ArtifactResolutionResult.Failure(
                "Plugins directory does not exist or is not a directory: ${pluginsDir.absolutePath}"
            )
        }

        val jarFiles = pluginsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".jar", ignoreCase = true)
        } ?: emptyArray()

        if (jarFiles.isEmpty()) {
            return ArtifactResolutionResult.Failure(
                "No JAR files found in plugins directory: ${pluginsDir.absolutePath}"
            )
        }

        val expectedPluginName = plugin.name
        val expectedMainClass = try { plugin.description.main } catch (_: Throwable) { null }

        val matchingCandidates = mutableListOf<Pair<File, String>>()

        for (jar in jarFiles) {
            // Validate plugin metadata from inside the JAR file
            if (!matchesPluginMetadata(jar, expectedPluginName, expectedMainClass)) {
                continue
            }

            // Compute candidate digest
            val hash = try {
                RuntimeFingerprint.computeSha256(jar)
            } catch (t: Throwable) {
                logger?.warning("[Optimizer] Failed to compute hash of candidate JAR ${jar.name}: ${t.message}")
                continue
            }

            if (hash.equals(expectedSha256, ignoreCase = true)) {
                matchingCandidates.add(jar to hash)
            }
        }

        return when {
            matchingCandidates.isEmpty() -> {
                ArtifactResolutionResult.Failure(
                    "No approved original artifact found in ${pluginsDir.path} for plugin '$expectedPluginName' matching expected SHA-256 $expectedSha256 (checked ${jarFiles.size} candidate JARs)"
                )
            }
            matchingCandidates.size > 1 -> {
                ArtifactResolutionResult.Failure(
                    "Ambiguous original artifact resolution: multiple JARs in ${pluginsDir.path} match metadata and approved SHA-256 for '$expectedPluginName': ${matchingCandidates.map { it.first.name }}"
                )
            }
            else -> {
                val (matchedFile, matchedHash) = matchingCandidates.first()
                logger?.info(
                    "[Optimizer] Resolved original installed artifact for '$expectedPluginName' at ${matchedFile.absolutePath} matching approved SHA-256."
                )
                ArtifactResolutionResult.Success(
                    file = matchedFile,
                    sha256 = matchedHash,
                    resolutionMethod = "PLUGINS_DIRECTORY_VERIFIED"
                )
            }
        }
    }

    companion object {
        fun getCodeSourceFile(plugin: Plugin): File? {
            val codeSource = try {
                plugin.javaClass.protectionDomain?.codeSource
            } catch (_: Throwable) {
                null
            } ?: return null

            val location = codeSource.location ?: return null
            return try {
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
        }
    }

    private fun matchesPluginMetadata(
        jarFile: File,
        expectedPluginName: String,
        expectedMainClass: String?
    ): Boolean {
        return try {
            JarFile(jarFile).use { jar ->
                val entry = jar.getJarEntry("plugin.yml") ?: jar.getJarEntry("paper-plugin.yml") ?: return false
                jar.getInputStream(entry).use { input ->
                    val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                    var nameMatches = false
                    var mainMatches = false
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("name:")) {
                            val name = trimmed.substringAfter("name:").trim().trim('\'', '"')
                            if (name.equals(expectedPluginName, ignoreCase = true)) {
                                nameMatches = true
                            }
                        }
                        if (expectedMainClass != null && trimmed.startsWith("main:")) {
                            val main = trimmed.substringAfter("main:").trim().trim('\'', '"')
                            if (main == expectedMainClass) {
                                mainMatches = true
                            }
                        }
                    }
                    nameMatches && (expectedMainClass == null || mainMatches)
                }
            }
        } catch (_: Throwable) {
            false
        }
    }
}
