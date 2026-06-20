plugins {
    id("codexlite.kmp-library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":auth"))
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
