plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-view-image-contract"))
            api(project(":tool-contract"))
            api(project(":tool-tool-builder"))
            api(project(":utils-images"))
            api(project(":utils-images-codec"))
            api(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
