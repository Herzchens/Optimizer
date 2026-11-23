package itzherzchen.optimizer.parity

import itzherzchen.optimizer.bootstrap.RuntimeFingerprint
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.writeText

class VendorParityTest {

    companion object {
        const val EXPECTED_WILDTOOLS_SHA = "dd9ca6393ab5824a4e00def7846697770e4bb546dbf79e8d15957acd968c829e"
        const val EXPECTED_SSB_SHA = "c7fb750ee60078175384baf4afcbde4f9fa066569bf045f516cefff7e6872168"
        const val WT_JAR_NAME = "WildTools-CUSTOM-2026.1.jar"
        const val SSB_JAR_NAME = "SuperiorSkyblock2-2026.3-b963.jar"

        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun validateVendorDirectory(libsDir: File) {
            require(libsDir.exists() && libsDir.isDirectory) {
                "libs directory does not exist: ${libsDir.absolutePath}"
            }
            val jarFiles = libsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.toList() ?: emptyList()
            require(jarFiles.isNotEmpty()) { "No JAR files found in ${libsDir.absolutePath}" }

            val approved = mapOf(
                WT_JAR_NAME to EXPECTED_WILDTOOLS_SHA,
                SSB_JAR_NAME to EXPECTED_SSB_SHA
            )

            for ((expectedName, expectedHash) in approved) {
                val jar = File(libsDir, expectedName)
                require(jar.exists()) { "Missing approved vendor JAR: $expectedName" }
                val actualHash = computeFileSha256(jar)
                require(actualHash.equals(expectedHash, ignoreCase = true)) {
                    "SHA-256 mismatch for $expectedName: expected $expectedHash but got $actualHash"
                }
            }

            val unexpected = jarFiles.filter { it.name !in approved.keys }
            require(unexpected.isEmpty()) {
                "Unexpected or ambiguous vendor JARs found in libs: ${unexpected.map { it.name }}"
            }
        }

        fun isReturnTypeCompatible(stubRet: String, realRet: String): Boolean {
            if (stubRet == realRet) return true
            if (stubRet == "com.bgsoftware.superiorskyblock.core.key.types.MaterialKey" && realRet == "com.bgsoftware.superiorskyblock.api.key.Key") return true
            if (stubRet == "com.bgsoftware.superiorskyblock.api.key.Key" && realRet == "com.bgsoftware.superiorskyblock.core.key.types.MaterialKey") return true
            return false
        }
    }

    @Test
    fun `local approved vendor binaries strictly match expected SHA-256 pins`() {
        val libsDir = File("libs")
        if (!libsDir.exists() || libsDir.listFiles()?.isEmpty() == true) {
            // Running in CI / headless environment without proprietary jars
            return
        }

        val wtJar = File(libsDir, WT_JAR_NAME)
        assertTrue(wtJar.exists(), "WildTools jar must exist in libs")
        assertEquals(EXPECTED_WILDTOOLS_SHA, computeFileSha256(wtJar).lowercase())

        val ssbJar = File(libsDir, SSB_JAR_NAME)
        assertTrue(ssbJar.exists(), "SuperiorSkyblock2 jar must exist in libs")
        assertEquals(EXPECTED_SSB_SHA, computeFileSha256(ssbJar).lowercase())
    }

    @Test
    fun `validateVendorDirectory rejects missing jar, hash mismatch, or extra jar`(@TempDir tempDir: Path) {
        val mockLibs = tempDir.toFile()

        // Empty directory
        assertThrows(IllegalArgumentException::class.java) {
            validateVendorDirectory(mockLibs)
        }

        // Only one jar present
        val mockWt = File(mockLibs, WT_JAR_NAME)
        mockWt.writeText("fake-wt")
        assertThrows(IllegalArgumentException::class.java) {
            validateVendorDirectory(mockLibs)
        }

        // Both present but hash mismatch
        val mockSsb = File(mockLibs, SSB_JAR_NAME)
        mockSsb.writeText("fake-ssb")
        assertThrows(IllegalArgumentException::class.java) {
            validateVendorDirectory(mockLibs)
        }

        // Extra unexpected jar
        val extraJar = File(mockLibs, "UnknownPlugin-1.0.jar")
        extraJar.writeText("extra")
        assertThrows(IllegalArgumentException::class.java) {
            validateVendorDirectory(mockLibs)
        }
    }

    @Test
    fun `stub definitions have 100 percent ABI parity with real vendor binaries`() {
        val libsDir = File("libs")
        val wtJar = File(libsDir, WT_JAR_NAME)
        val ssbJar = File(libsDir, SSB_JAR_NAME)
        if (!wtJar.exists() || !ssbJar.exists()) {
            // Running in CI / headless environment
            return
        }

        val stubClassesDir = File("build/classes/java/stub")
        val stubClassLoader = URLClassLoader(
            arrayOf(stubClassesDir.toURI().toURL()),
            org.bukkit.Bukkit::class.java.classLoader
        )

        val vendorClassLoader = URLClassLoader(
            arrayOf(wtJar.toURI().toURL(), ssbJar.toURI().toURL()),
            org.bukkit.Bukkit::class.java.classLoader
        )

        val stubRootDir = File("src/stub/java")
        assertTrue(stubRootDir.exists(), "src/stub/java directory must exist")

        val stubClassNames = stubRootDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .map { file ->
                val relPath = file.relativeTo(stubRootDir).path
                relPath.removeSuffix(".java").replace(File.separatorChar, '.')
            }
            .toList()

        assertTrue(stubClassNames.isNotEmpty(), "Stub class list must not be empty")

        for (className in stubClassNames) {
            val stubClass = try {
                stubClassLoader.loadClass(className)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("Failed to load stub class $className from $stubClassesDir", e)
            }

            val realClass = try {
                vendorClassLoader.loadClass(className)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("Stub class $className does NOT exist in real vendor JARs!", e)
            }

            assertNotNull(realClass, "Real vendor class $className must exist")

            // Verify all public/protected methods declared in stub exist in real class
            for (stubMethod in stubClass.declaredMethods) {
                if (stubMethod.isBridge || stubMethod.isSynthetic) continue
                val mod = stubMethod.modifiers
                if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue

                val stubRet = stubMethod.returnType.name

                val realMethods = realClass.methods.filter { it.name == stubMethod.name }
                val matchingMethod = realMethods.firstOrNull { realM ->
                    realM.parameterTypes.map { it.name } == stubMethod.parameterTypes.map { it.name } &&
                            isReturnTypeCompatible(stubRet, realM.returnType.name)
                } ?: realClass.declaredMethods.filter { it.name == stubMethod.name }.firstOrNull { realM ->
                    realM.parameterTypes.map { it.name } == stubMethod.parameterTypes.map { it.name } &&
                            isReturnTypeCompatible(stubRet, realM.returnType.name)
                }

                assertNotNull(
                    matchingMethod,
                    "Method '${stubMethod.name}(${stubMethod.parameterTypes.joinToString { it.simpleName }})' in stub $className not found with compatible return type in real vendor class!"
                )
            }

            // Verify all public/protected fields in stub exist in real class
            for (stubField in stubClass.declaredFields) {
                if (stubField.isSynthetic) continue
                val mod = stubField.modifiers
                if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue

                val realField = try {
                    realClass.getField(stubField.name)
                } catch (_: NoSuchFieldException) {
                    try {
                        realClass.getDeclaredField(stubField.name)
                    } catch (e: NoSuchFieldException) {
                        throw AssertionError(
                            "Field '${stubField.name}' in stub class $className not found in real vendor class!",
                            e
                        )
                    }
                }

                assertEquals(
                    stubField.type.name,
                    realField.type.name,
                    "Type mismatch for field '${stubField.name}' on class $className"
                )
            }
        }
    }

    @Test
    fun `SyntheticListenerInstaller default thread checker fails closed`() {
        val modeController = itzherzchen.optimizer.runtime.ModeController()
        val breaker = itzherzchen.optimizer.runtime.CircuitBreaker(modeController)
        val counters = itzherzchen.optimizer.observe.Counters()
        val stack = itzherzchen.optimizer.runtime.ActionContextStack(breaker, counters, primaryThreadChecker = { true })

        val defaultInstaller = itzherzchen.optimizer.wildtools.SyntheticListenerInstaller(
            stack, modeController, breaker, counters
        )
        val dummyList = java.util.LinkedList<org.bukkit.plugin.RegisteredListener>()
        val dummyCensus = itzherzchen.optimizer.bootstrap.ListenerCensusResult(
            isTopologyValid = false,
            entries = emptyList(),
            stackedBlocksIndex = -1,
            blockChangesIndex = -1,
            signsListenerIndex = -1,
            protectionListenerIndex = -1,
            discrepancies = emptyList()
        )
        val result = defaultInstaller.install(dummyList, dummyCensus)
        assertEquals(
            itzherzchen.optimizer.wildtools.InstallResult.THREAD_SAFETY_VIOLATION,
            result,
            "SyntheticListenerInstaller must fail closed with THREAD_SAFETY_VIOLATION when unable to prove primary thread"
        )
    }

    @Test
    fun `Optimizer compiled bytecode references to vendor APIs exist in real vendor binaries`() {
        val libsDir = File("libs")
        val wtJar = File(libsDir, WT_JAR_NAME)
        val ssbJar = File(libsDir, SSB_JAR_NAME)
        if (!wtJar.exists() || !ssbJar.exists()) {
            return
        }

        val mainClassesDir = File("build/classes/kotlin/main")
        if (!mainClassesDir.exists()) {
            return
        }

        val vendorClassLoader = URLClassLoader(
            arrayOf(mainClassesDir.toURI().toURL(), wtJar.toURI().toURL(), ssbJar.toURI().toURL()),
            org.bukkit.Bukkit::class.java.classLoader
        )

        val optimizerClasses = mainClassesDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { file ->
                val relPath = file.relativeTo(mainClassesDir).path
                relPath.removeSuffix(".class").replace(File.separatorChar, '.')
            }
            .toList()

        assertTrue(optimizerClasses.isNotEmpty(), "Compiled Optimizer classes must exist")

        for (className in optimizerClasses) {
            val clazz = Class.forName(className, false, vendorClassLoader)
            assertNotNull(clazz, "Class $className must load cleanly against vendor classloader")
            assertDoesNotThrow {
                clazz.declaredMethods
                clazz.declaredFields
                clazz.declaredConstructors
            }
        }
    }
}
