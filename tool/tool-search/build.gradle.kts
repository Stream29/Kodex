plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool:contract"))
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.schema.json)
            implementation(project(":utils:search-index"))
        }
    }
}
