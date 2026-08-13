plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-path-picker"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
