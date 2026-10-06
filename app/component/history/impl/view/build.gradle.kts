plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-agent-spec"))
            api(project(":app-component-history-spec"))
            api(project(":agent-storage-spec-clean-models"))
            api(project(":utils-patch-spec"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-impl-in-memory"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(libs.kotlin.logging)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":agent-session-impl-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-spec-contract-ext"))
            implementation(project(":app-component-history-impl-viewmodel"))
            implementation(project(":utils-coroutines-spec"))
            implementation(libs.mosaic.testing)
        }
    }
}
