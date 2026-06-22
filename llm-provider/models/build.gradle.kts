plugins {
    id("codexlite.kmp-shared")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(project(":tool:contract"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
