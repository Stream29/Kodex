plugins { id("kodex.kmp-viewmodel") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-authentication-settings-spec")) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
