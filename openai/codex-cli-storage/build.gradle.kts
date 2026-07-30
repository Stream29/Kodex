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
            api(project(":openai-models"))
            api(project(":utils-kotlinx-io-serialization"))
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.tomlkt)
            implementation(project(":openai-json-codec"))
            implementation(project(":utils-kotlinx-io-coroutines"))
            implementation(project(":utils-os-environment"))
        }
        jsMain.dependencies {
            implementation(libs.kotlin.wrappers.node)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
