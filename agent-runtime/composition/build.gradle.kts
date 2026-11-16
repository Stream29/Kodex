plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain") {
            dependencies {
                api(project(":agent-runtime-contract"))
                api(project(":agent-session-contract"))
                api(project(":agent-state-contract"))
            }
        }
        commonMain.dependencies {
            implementation(project(":agent-state-core"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":openai-models"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-await-event"))
            implementation(project(":tool-core"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-read-media"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-logging-backend-scope"))
            implementation(libs.kotlin.logging)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-state-core"))
            implementation(project(":agent-state-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":openai-client-test"))
            implementation(project(":openai-json-codec"))
            implementation(project(":openai-model-catalog"))
            implementation(project(":tool-apply-patch"))
            implementation(project(":tool-plan"))
            implementation(project(":tool-read-media"))
            implementation(project(":tool-unified-exec"))
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-shell-client-system"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.schema.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
