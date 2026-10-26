plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-account-usage-viewmodel"))
            api(compose.runtime)
        }
        commonMain.dependencies {
            implementation(project(":app-component-widgets"))
        }


    }
}
