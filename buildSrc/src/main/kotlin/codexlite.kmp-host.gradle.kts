import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val currentNativeHostTargetSuffix = when {
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "LinuxX64"
    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "LinuxArm64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "MacosArm64"
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
        System.getProperty("os.arch") in setOf("amd64", "x86_64") -> "MingwX64"
    else -> null
}

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "io.github.stream29"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    jvmToolchain(26)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("26"))
        }
    }

    js {
        nodejs {
            testTask {
                useMocha {
                    timeout = "30s"
                }
            }
        }
        binaries.library()
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val nativeHostTargetSuffixes = setOf("LinuxX64", "LinuxArm64", "MacosArm64", "MingwX64")

tasks.configureEach {
    val targetSuffix = nativeHostTargetSuffixes.firstOrNull { name.endsWith(it) } ?: return@configureEach
    val targetTestTaskName = targetSuffix.replaceFirstChar { it.lowercaseChar() } + "Test"
    val isNativeTestLinkOrRun = name == targetTestTaskName || (name.startsWith("link") && name.contains("Test"))

    // Native tests validate the current host. Cross-linking test binaries can pull platform
    // native libraries through a foreign toolchain and fail for reasons unrelated to the code.
    if (isNativeTestLinkOrRun && targetSuffix != currentNativeHostTargetSuffix) {
        enabled = false
    }
}
