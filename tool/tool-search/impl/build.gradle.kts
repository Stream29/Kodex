plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-tool-search-contract"))
            api(project(":tool-contract"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-search-index"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
