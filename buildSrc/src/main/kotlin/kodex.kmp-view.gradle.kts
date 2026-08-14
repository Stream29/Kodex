import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("kodex.kmp-cli")
    kotlin("plugin.compose")
}

val libs = extensions
    .getByType<VersionCatalogsExtension>()
    .named("libs")

kotlin {
    configureMosaicHierarchy()

    sourceSets {
        getByName("desktopMain").dependencies {
            implementation(libs.findLibrary("compose-material3").get())
        }
    }
}

if (providers.gradleProperty("kodex.composeReports").orNull == "true") {
    composeCompiler {
        reportsDestination.set(layout.buildDirectory.dir("compose/reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))
    }
}
