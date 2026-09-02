plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-context-prefix-agents-md-contract"))
            api(project(":utils-kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
