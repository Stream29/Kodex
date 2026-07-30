plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-contract"))
            api(project(":agent-context-skill-contract"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-prefix-skill-contract"))
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-prefix-skill-contract"))
            implementation(project(":utils-kotlinx-io-coroutines"))
        }
    }
}
