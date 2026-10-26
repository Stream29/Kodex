plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-component-view")
    id("kodex.kmp-tests")
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-history-presentation"))
            api(project(":app-component-agent-contract"))
            api(project(":app-component-history-viewmodel"))
            api(project(":agent-storage-clean-models"))
            api(project(":utils-patch"))
            api(project(":tool-unified-exec-contract"))
            api(project(":app-component-widgets"))
            api(compose.runtime)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-core"))
        }
    }
}
