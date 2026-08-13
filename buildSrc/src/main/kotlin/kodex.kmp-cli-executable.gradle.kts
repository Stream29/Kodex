import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kodex.kmp-cli")
    kotlin("plugin.compose")
}

kotlin {
    configureMosaicHierarchy()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "kodex-cli"
            entryPoint = "io.github.stream29.kodex.cli.app.main"
        }
    }
}
