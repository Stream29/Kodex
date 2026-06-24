plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":tool:contract"))
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.schema.json)
                implementation(project(":utils:search-index"))
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
