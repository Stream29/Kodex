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
            api(project(":hook-spec-hooks"))
            api(project(":agent-storage-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":utils-coroutines-spec"))
            implementation(project(":utils-shell-client-impl"))
            implementation(project(":utils-logging-impl"))
            implementation(project(":openai-spec-json-codec"))
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
