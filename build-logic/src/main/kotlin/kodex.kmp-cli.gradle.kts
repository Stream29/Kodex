import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

configureCoordinates()

// Temporary accepted-socket repair; root/JVM and other Ktor modules stay official.
val nativeNetworkVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findVersion("ktor-network-native").get().requiredVersion
configurations.configureEach {
    resolutionStrategy.force(
        "io.ktor:ktor-network-linuxx64:$nativeNetworkVersion",
        "io.ktor:ktor-network-linuxarm64:$nativeNetworkVersion",
        "io.ktor:ktor-network-macosarm64:$nativeNetworkVersion",
        "io.ktor:ktor-network-mingwx64:$nativeNetworkVersion",
    )
}

kotlin {
    configureCompiler()
    configureHostTargets()
}
