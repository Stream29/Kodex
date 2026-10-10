plugins { id("kodex.kmp-viewmodel"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-application-preferences-spec")) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
