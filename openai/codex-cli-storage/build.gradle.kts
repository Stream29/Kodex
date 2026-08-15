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
            api(project(":openai-models"))
            api(project(":utils-kotlinx-io-coroutines"))
            api(libs.kotlinx.io.core)
            api(libs.tomlkt)
            implementation(project(":openai-json-codec"))
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
        }
        commonTest.dependencies {
            implementation(project(":utils-os-environment"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
