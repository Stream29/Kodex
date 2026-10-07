plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-context-spec-contract"))
            api(project(":agent-context-spec-skill"))
            api(project(":utils-kotlinx-io-coroutines-impl"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":agent-context-spec-available-skill"))
        }
        commonTest.dependencies {
            implementation(project(":agent-context-spec-available-skill"))
            implementation(project(":utils-kotlinx-io-coroutines-impl"))
        }
    }
}
