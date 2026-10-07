plugins { id("kodex.kmp-viewmodel") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":app-component-runtime-configuration-spec")) }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
