plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-view-image-contract"))
            api(project(":tool-contract"))
            api(project(":utils-images"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-images-codec"))
        }
    }
}
