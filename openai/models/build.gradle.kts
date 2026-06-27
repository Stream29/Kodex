plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(project(":openai:json-codec"))
        }
    }
}
