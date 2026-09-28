plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rpc-models"))
            api(project(":utils-shell-client"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
