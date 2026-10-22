plugins {
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-view")
    id("kodex.kmp-tests")
}

kotlin {
    sourceSets {
        named("specMain").dependencies {
            api(project(":app-component-agent-contract"))
            api(project(":app-component-history-viewmodel"))
            api(project(":agent-storage-clean-models"))
            api(project(":utils-patch"))
            api(project(":app-view-components"))
            api(libs.mosaic.runtime)
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-core"))
        }
        mosaicMain.dependencies {
            implementation(libs.kotlin.logging)
            implementation(libs.mosaic.runtime)
        }
        mosaicTest.dependencies {
            implementation(project(":agent-session-in-memory"))
            implementation(project(":agent-session-test"))
            implementation(project(":agent-storage-core"))
            implementation(project(":app-component-history-viewmodel"))
            implementation(project(":utils-coroutines"))
            implementation(libs.mosaic.testing)
        }
    }
}
