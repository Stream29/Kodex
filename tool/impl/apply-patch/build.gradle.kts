plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-spec-apply-patch"))
            api(project(":tool-spec-contract"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(project(":utils-patch-impl"))
        }
    }
}
