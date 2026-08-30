plugins {
    id("kodex.kmp-host")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":agent-storage-contract"))
        }
        commonTest.dependencies {
            implementation(project(":agent-storage-in-memory"))
        }
    }
}
