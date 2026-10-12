plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-viewmodel")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":hook-spec-notification"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    }
}
