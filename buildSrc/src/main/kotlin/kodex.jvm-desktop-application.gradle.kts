@file:Suppress("UnstableApiUsage")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.gradleup.shadow")
}

configureCoordinates()

val libs = extensions
    .getByType<VersionCatalogsExtension>()
    .named("libs")

val desktopJavaVersion = JavaLanguageVersion.of(25)
val desktopJvmVendor = JvmVendorSpec.JETBRAINS

kotlin {
    explicitApi()
    jvmToolchain {
        languageVersion.set(desktopJavaVersion)
        vendor.set(desktopJvmVendor)
    }

    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

val desktopJbrLauncher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(desktopJavaVersion)
        vendor.set(desktopJvmVendor)
    }
val desktopJbrHome = desktopJbrLauncher.map {
    it.metadata.installationPath.asFile.absolutePath
}
val desktopJbrExecutable = desktopJbrLauncher.map {
    it.executablePath.asFile.absolutePath
}
val desktopAllRuntime = configurations.create("desktopAllRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(compose.desktop.currentOs)
    add(desktopAllRuntime.name, compose.desktop.linux_x64)
    add(desktopAllRuntime.name, compose.desktop.macos_arm64)
    add(desktopAllRuntime.name, compose.desktop.windows_x64)
    implementation(libs.findLibrary("compose-material3").get())
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "io.github.stream29.kodex.desktop.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Kodex"
            packageVersion = project.version.toString()
            description = "Kodex agent desktop application"
            vendor = "stream29"
        }
    }
}

afterEvaluate {
    tasks.withType<JavaExec>().configureEach {
        javaLauncher.set(desktopJbrLauncher)
        executable(desktopJbrExecutable.get())
    }
    tasks.withType<AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(desktopJbrHome)
    }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().configureEach {
        jdkHome.set(desktopJbrHome)
    }
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(desktopJbrHome)
    }
    tasks.withType<AbstractSuggestModulesTask>().configureEach {
        javaHome.set(desktopJbrHome)
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<ShadowJar>("shadowJar") {
    configurations = project.configurations.named("runtimeClasspath").map {
        listOf(it, desktopAllRuntime)
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    manifest {
        attributes["Main-Class"] = "io.github.stream29.kodex.desktop.MainKt"
    }
}
