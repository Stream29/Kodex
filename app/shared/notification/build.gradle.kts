plugins {
    id("kodex.kmp-cli")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rpc-spec-models"))
            api(project(":utils-shell-client-impl"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
