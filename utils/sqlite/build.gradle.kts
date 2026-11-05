import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kodex.spec-api-module")
    id("kodex.kmp-spec-impl")
    id("kodex.kmp-host")
    id("kodex.kmp-tests")
}

kotlin {
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    sourceSets.named("specMain").dependencies { api(libs.kotlinx.io.core) }
    sourceSets.nativeMain.dependencies { implementation(libs.sqliter.driver) }
    sourceSets.commonTest.dependencies { implementation(libs.kotlinx.coroutines.test) }
    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val output = layout.buildDirectory.dir("sqlite/$targetName")
        val canBuildOnHost = targetName != "macosArm64" || System.getProperty("os.name") == "Mac OS X"
        val compileSqlite = tasks.register<Exec>("compileBundledSqlite${targetName.replaceFirstChar { it.uppercase() }}") {
            onlyIf { canBuildOnHost }
            inputs.dir("src/nativeInterop")
            outputs.dir(output)
            commandLine("bash", project.file("src/nativeInterop/build-sqlite.sh"), targetName, output.get().asFile)
        }
        compilations["main"].cinterops.create("bundledSqlite") {
            definitionFile.set(output.map { it.file("bundled.def") })
            tasks.named(interopProcessingTaskName).configure { dependsOn(compileSqlite) }
        }
    }
}
