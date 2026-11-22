import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "itzherzchen.optimizer"
version = "0.1.0-SNAPSHOT"

val localLibs = fileTree("libs") { include("*.jar") }
val hasLocalLibs = localLibs.files.isNotEmpty()

val stubSourceSet = if (!hasLocalLibs) {
    sourceSets.create("stub") {
        java.srcDir("src/stub/java")
    }
} else null

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))

    if (hasLocalLibs) {
        compileOnly(localLibs)
        testImplementation(localLibs)
    } else if (stubSourceSet != null) {
        "stubCompileOnly"("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
        compileOnly(stubSourceSet.output)
        testImplementation(stubSourceSet.output)
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
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

tasks.shadowJar {
    archiveClassifier.set("")
    // Shade Kotlin stdlib, do not relocate kotlin.*, do not minimize
    mergeServiceFiles()
    exclude("com/bgsoftware/**")
}

val verifyPluginJar by tasks.registering {
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

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.check {
    dependsOn(verifyPluginJar)
}

tasks.build {
    dependsOn(verifyPluginJar)
}

tasks.wrapper {
    gradleVersion = "8.14.4"
    distributionType = Wrapper.DistributionType.BIN
}

