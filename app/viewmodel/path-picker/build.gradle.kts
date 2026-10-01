plugins {
    id("kodex.kmp-viewmodel")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-contract-path-picker"))
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-os-environment-impl"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
