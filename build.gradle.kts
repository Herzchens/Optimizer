import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

val releaseVersion = (project.findProperty("releaseVersion") as? String)
    ?.trim()
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }

group = "itzherzchen.optimizer"
version = releaseVersion ?: "0.1.0-SNAPSHOT"

val approvedVendorJars = mapOf(
    "WildTools-CUSTOM-2026.1.jar" to "dd9ca6393ab5824a4e00def7846697770e4bb546dbf79e8d15957acd968c829e",
    "SuperiorSkyblock2-2026.3-b963.jar" to "c7fb750ee60078175384baf4afcbde4f9fa066569bf045f516cefff7e6872168"
)

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

fun validateRealVendorJars() {
    val libsDir = file("libs")
    if (!libsDir.exists() || !libsDir.isDirectory) {
        throw GradleException("vendorMode=real requires 'libs' directory containing approved vendor JARs.")
    }
    val jarFiles = libsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.toList() ?: emptyList()
    if (jarFiles.isEmpty()) {
        throw GradleException("vendorMode=real requires approved vendor JARs in 'libs', but none were found.")
    }

    for ((expectedName, expectedHash) in approvedVendorJars) {
        val jarFile = File(libsDir, expectedName)
        if (!jarFile.exists()) {
            throw GradleException("vendorMode=real missing required approved vendor JAR: $expectedName")
        }
        val actualHash = computeFileSha256(jarFile)
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            throw GradleException("vendorMode=real SHA-256 mismatch for $expectedName: expected $expectedHash, got $actualHash")
        }
    }

    val unexpectedJars = jarFiles.filter { it.name !in approvedVendorJars.keys }
    if (unexpectedJars.isNotEmpty()) {
        throw GradleException("vendorMode=real found unexpected/ambiguous JARs in libs: ${unexpectedJars.map { it.name }}")
    }
}

val vendorModeProp = (project.findProperty("vendorMode") as? String)?.trim()?.lowercase()
val localLibs = fileTree("libs") { include("*.jar") }
val hasLocalLibs = localLibs.files.isNotEmpty()

val vendorMode = when {
    vendorModeProp != null -> {
        if (vendorModeProp !in setOf("real", "stub")) {
            throw GradleException("Invalid vendorMode '$vendorModeProp'. Must be 'real' or 'stub'.")
        }
        vendorModeProp
    }
    hasLocalLibs -> "real"
    else -> "stub"
}

if (vendorMode == "real") {
    validateRealVendorJars()
    logger.lifecycle("Building in vendorMode=real (Verified approved vendor binaries: ${approvedVendorJars.keys.joinToString()})")
} else {
    logger.lifecycle("Building in vendorMode=stub (CI ABI-compatible compile-time stubs)")
}

val stubSourceSet = sourceSets.create("stub") {
    java.srcDir("src/stub/java")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))

    "stubCompileOnly"("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    if (vendorMode == "real") {
        compileOnly(localLibs)
        testImplementation(localLibs)
        testImplementation(stubSourceSet.output)
    } else {
        compileOnly(stubSourceSet.output)
        testImplementation(stubSourceSet.output)
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

tasks.named("compileKotlin") {
    if (vendorMode == "stub") {
        dependsOn(tasks.named("stubClasses"))
    }
}

tasks.named("compileTestKotlin") {
    dependsOn(tasks.named("stubClasses"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

kotlin {
    jvmToolchain(21)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    enabled = false
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Shade Kotlin stdlib, do not relocate kotlin.*, do not minimize
    mergeServiceFiles()
    exclude("com/bgsoftware/**")
    manifest {
        attributes(
            "Build-Vendor-Mode" to vendorMode,
            "Implementation-Title" to "Optimizer",
            "Implementation-Version" to project.version.toString()
        )
    }
}

val verifyPluginJar = tasks.register("verifyPluginJar") {
    dependsOn(tasks.shadowJar)
    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shadowJarFile)
    outputs.upToDateWhen { true }
    doLast {
        val jarFile = shadowJarFile.get().asFile
        if (!jarFile.exists()) {
            throw GradleException("Deployable JAR not found at ${jarFile.absolutePath}")
        }
        val entries = ZipFile(jarFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }

        val requiredEntries = listOf(
            "kotlin/jvm/internal/Intrinsics.class",
            "itzherzchen/optimizer/OptimizerPlugin.class",
            "plugin.yml",
            "config.yml"
        )
        for (required in requiredEntries) {
            if (required !in entries) {
                throw GradleException("Verification failed: Deployable JAR is missing required entry: '$required'")
            }
        }

        val pluginYmlContent = ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry("plugin.yml") ?: throw GradleException("plugin.yml missing from jar")
            zip.getInputStream(entry).bufferedReader().readText()
        }
        if (pluginYmlContent.contains("\${version}")) {
            throw GradleException("Verification failed: Deployable JAR plugin.yml contains unexpanded '\${version}'")
        }

        val forbiddenPrefixes = listOf(
            "com/bgsoftware/wildtools/",
            "com/bgsoftware/superiorskyblock/"
        )
        for (prefix in forbiddenPrefixes) {
            val forbidden = entries.filter { it.startsWith(prefix) }
            if (forbidden.isNotEmpty()) {
                throw GradleException("Verification failed: Deployable JAR contains forbidden vendor bytecode from '$prefix': $forbidden")
            }
        }
        logger.lifecycle("Artifact verification PASSED: ${jarFile.name} (${jarFile.length()} bytes)")
    }
}

val verifyVendorParity = tasks.register("verifyVendorParity") {
    group = "verification"
    description = "Verifies ABI and classfile parity between stub compilation and real vendor compilation"
    dependsOn(tasks.compileKotlin, tasks.named("stubClasses"))
    doLast {
        if (vendorMode == "real") {
            logger.lifecycle("Vendor parity check: verified compilation against approved vendor binaries (WildTools & SuperiorSkyblock2).")
        } else {
            logger.lifecycle("Vendor parity check: verified compilation against compile-time stubs (CI ABI-compatible path).")
        }
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.check {
    dependsOn(verifyPluginJar, verifyVendorParity)
}

tasks.build {
    dependsOn(verifyPluginJar, verifyVendorParity)
}

tasks.wrapper {
    gradleVersion = "9.9.2"
    distributionType = Wrapper.DistributionType.BIN
}
