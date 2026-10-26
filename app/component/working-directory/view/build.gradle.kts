plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-working-directory-viewmodel"))
            api(project(":app-component-path-picker-view"))
            api(compose.runtime)
        }
    }
}
