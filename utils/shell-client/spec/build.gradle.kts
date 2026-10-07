plugins {
    id("kodex.kmp-shared")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-process-client-spec"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
