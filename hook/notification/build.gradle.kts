plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
    id("kodex.krpc-utils-patch")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            api(project(":utils-shell-client-system"))
            implementation(project(":utils-coroutines"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(project(":rpc-in-memory"))
            implementation(project(":rpc-client"))
            implementation(project(":rpc-server"))
            implementation(project(":openai-client-test"))
        }
    }
}
