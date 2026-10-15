plugins {
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-impl-backend-daemon"))
            implementation(project(":rpc-impl-local-server"))
            implementation(project(":rpc-impl-backend-control-client"))
            implementation(project(":rpc-impl-server"))
            implementation(project(":app-migration-impl"))
            implementation(project(":app-settings-impl-filesystem"))
            implementation(project(":hook-impl-notification"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":utils-os-environment-spec"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":openai-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(project(":rpc-impl-local"))
            implementation(project(":rpc-impl-backend-control-client"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":openai-client-test"))
            implementation(project(":app-impl-rpc"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":rpc-impl-client"))
        }
        jvmTest.dependencies {
            implementation(project(":app-impl-application"))
        }
    }
}
