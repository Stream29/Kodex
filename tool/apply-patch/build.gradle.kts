plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":tool-contract"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(project(":utils-patch"))
        }
    }
}
