plugins {
    id("kodex.kmp-web")
    kotlin("plugin.compose")
}

kotlin {
    val composeUiMain = sourceSets.create("composeUiMain") {
        dependsOn(sourceSets.getByName("commonMain"))
    }
    sourceSets.getByName("wasmJsMain").dependsOn(composeUiMain)
}
