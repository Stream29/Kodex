plugins { id("kodex.kmp-cli"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":hook-spec-notification"))
            api(project(":rpc-spec-models"))
            api(project(":utils-shell-client-impl"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(project(":rpc-impl-in-memory"))
            implementation(project(":rpc-impl-client"))
            implementation(project(":rpc-impl-server"))
            implementation(project(":openai-impl-client-test"))
        }
    }
}
