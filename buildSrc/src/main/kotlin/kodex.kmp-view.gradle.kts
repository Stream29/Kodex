plugins {
    id("kodex.kmp-cli")
    kotlin("plugin.compose")
}

kotlin {
    configureMosaicHierarchy()
}

if (providers.gradleProperty("kodex.composeReports").orNull == "true") {
    composeCompiler {
        reportsDestination.set(layout.buildDirectory.dir("compose/reports"))
        metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))
    }
}
