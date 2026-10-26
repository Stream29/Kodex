plugins {
    id("kodex.kmp-frontend")
    kotlin("plugin.compose")
}

kotlin {
    configureMosaicHierarchy()
    val composeUiMain = sourceSets.create("composeUiMain") {
        dependsOn(sourceSets.getByName("commonMain"))
    }
    sourceSets.getByName("wasmJsMain").dependsOn(composeUiMain)
}
