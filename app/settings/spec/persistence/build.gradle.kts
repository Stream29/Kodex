plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":rpc-spec-models"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
    }
}
