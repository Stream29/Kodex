plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-cli")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(libs.kotlinx.io.core)
        }
        commonMain.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-system"))
            implementation(libs.kotlin.logging)
            implementation(libs.kermit.core)
            implementation(libs.kermit.io)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
