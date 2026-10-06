plugins {
    id("kodex.kmp-view")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-component-new-session-spec"))
        }
        mosaicMain.dependencies {
            implementation(project(":app-view-components"))
            implementation(project(":app-component-composer-impl-view"))
            implementation(project(":app-component-application-preferences-spec"))
            implementation(libs.mosaic.runtime)
        }
    }
}
