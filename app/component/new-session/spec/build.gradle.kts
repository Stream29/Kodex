plugins { id("kodex.kmp-cli") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":app-spec-session"))
            api(project(":app-component-composer-spec"))
            api(project(":app-component-runtime-configuration-spec"))
        }
    }
}
