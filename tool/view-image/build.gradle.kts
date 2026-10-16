plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-view-image-contract"))
            api(project(":tool-core"))
            api(project(":utils-images"))
            api(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":utils-images-codec"))
        }
    }
}
