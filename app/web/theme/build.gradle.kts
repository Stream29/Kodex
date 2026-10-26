plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-web")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(compose.runtime)
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.components.resources)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.stream29.kodex.app.ui.browser.resources"
}
