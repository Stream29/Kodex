plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":agent-context-spec-agents-md"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
