plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":openai-spec-models"))
            api(project(":app-component-path-picker-impl-viewmodel"))
            api(project(":app-component-working-directory-impl-viewmodel"))
            api(project(":app-component-session-rename-impl-viewmodel"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":app-component-working-directory-impl-viewmodel"))
            implementation(project(":app-component-session-rename-impl-viewmodel"))
            implementation(project(":utils-logging-impl"))
            implementation(libs.kotlin.logging)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
