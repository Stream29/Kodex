plugins {
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.rpc.core)
            api(libs.kotlinx.coroutines.core)
            api(project(":utils-rpc-exception-spec"))
            api(project(":rpc-spec-contract"))
            api(project(":agent-storage-contract"))
            implementation(libs.cache4k)
        }
        commonTest.dependencies {
            implementation(project(":rpc-impl-in-memory"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
