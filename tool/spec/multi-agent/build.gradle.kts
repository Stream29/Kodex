plugins {
    id("codexlite.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":openai-models"))
            implementation(libs.kotlinx.schema.json)
        }
        commonTest.dependencies {
            implementation(project(":openai-json-codec"))
        }
    }
}
