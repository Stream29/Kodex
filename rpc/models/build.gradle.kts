plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-frontend")
    alias(libs.plugins.kotlin.serialization)
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":app-rpc-protocol"))
                api(project(":agent-storage-clean-models"))
                api(project(":agent-context-settings"))
                api(project(":mcp-contract"))
                api(project(":utils-shell-client"))
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
