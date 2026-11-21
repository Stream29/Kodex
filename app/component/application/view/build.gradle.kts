plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-application-viewmodel"))
            api(project(":app-component-agent-view"))
            api(project(":app-component-composer-view"))
            api(project(":app-component-history-index-view"))
            api(project(":app-component-widgets"))
            api(libs.kotlinx.datetime)
            api(compose.runtime)
        }
        commonMain.dependencies {
            implementation(project(":app-component-settings-view"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
