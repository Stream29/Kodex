plugins { id("kodex.kmp-viewmodel"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-runtime-configuration-spec")) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
