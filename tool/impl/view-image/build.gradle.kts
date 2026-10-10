plugins {
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-view-image"))
            api(project(":tool-spec-contract"))
            api(project(":utils-images-spec"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.schema.json)
            implementation(project(":utils-images-codec-impl"))
        }
    }
}
