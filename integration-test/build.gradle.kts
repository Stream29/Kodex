plugins {
    id("codexlite.kmp-host")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(project(":openai:client-contract"))
            implementation(project(":openai:client-test"))
            implementation(project(":tool:contract"))
            implementation(project(":tool:impl:apply-patch"))
            implementation(project(":utils:kotlinx-io-coroutines"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
