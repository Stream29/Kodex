plugins {
    id("kodex.kmp-host")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":hook-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines"))
            implementation(project(":utils-shell-client"))
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
