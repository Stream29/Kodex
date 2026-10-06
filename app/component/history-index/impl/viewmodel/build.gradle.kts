plugins { id("kodex.kmp-viewmodel") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-history-index-spec"))
            implementation(project(":utils-rpc-exception-spec"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-impl-in-memory"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":agent-state-spec-contract"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
