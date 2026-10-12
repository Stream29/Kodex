plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":agent-storage-core"))
            api(project(":agent-storage-spec-clean-models"))
            api(libs.kotlinx.coroutines.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-rpc-exception-spec"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-core"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-state-spec-contract"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
