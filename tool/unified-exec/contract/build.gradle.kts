plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-shell-client"))
            api(libs.kotlinx.serialization.core)
        }
    }
}
