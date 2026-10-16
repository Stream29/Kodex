plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":openai-models"))
            api(project(":app-component-path-picker-viewmodel"))
            api(project(":app-component-working-directory-viewmodel"))
            api(project(":app-component-session-rename-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":app-component-working-directory-viewmodel"))
            implementation(project(":app-component-session-rename-viewmodel"))
            implementation(project(":utils-logging"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
