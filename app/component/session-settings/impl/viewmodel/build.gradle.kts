plugins { id("kodex.kmp-viewmodel"); id("kodex.kmp-tests") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-session-settings-spec"))
            implementation(project(":app-component-working-directory-impl-viewmodel"))
            implementation(project(":app-component-session-rename-impl-viewmodel"))
            implementation(project(":utils-logging-impl"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
