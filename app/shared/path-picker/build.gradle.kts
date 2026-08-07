plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
        }
    }
}
