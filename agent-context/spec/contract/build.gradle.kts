plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.core)
            api(project(":utils-shell-client-spec"))
            api(libs.kotlinx.io.core)
        }
    }
}
