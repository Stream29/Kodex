plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.krpc-utils-patch")
    id("kodex.kmp-cli")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":rpc-models"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            api(project(":utils-rpc-exception"))
            implementation(libs.cache4k)
        }
        commonTest.dependencies {
            implementation(project(":rpc-in-memory"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":rpc-models"))
                api(project(":app-component-authentication-settings-viewmodel"))
                api(project(":app-component-account-usage-viewmodel"))
                api(project(":app-component-session-catalog-viewmodel"))
                api(project(":mcp-contract"))
                api(project(":agent-storage-clean-models"))
                api(project(":agent-storage-core"))
                api(project(":openai-models"))
                api(project(":utils-kotlinx-io-serialization"))
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.rpc.core)
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(project(":utils-rpc-exception"))
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.server)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
