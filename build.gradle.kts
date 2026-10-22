import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

// Only actual kRPC consumers apply kodex.krpc-utils-patch.

dependencies {
    detektPlugins("io.github.stream29.kodex:kodex-lint:1.0")
    registerTransform(ContentAddressedDetektPlugin::class) {
        from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(ARTIFACT_TYPE_ATTRIBUTE, "detekt-plugin")
    }
}

// detekt alpha.6 caches rule classloaders by path; Gradle's transform cache gives
// changed JAR contents a new path without a new worker JVM on every analysis.
@CacheableTransform
abstract class ContentAddressedDetektPlugin : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        input.copyTo(outputs.file(input.name))
    }
}

val rulePlugins = configurations.getByName("detektPlugins").incoming.artifactView {
    attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "detekt-plugin")
}.files

val implementationSources = objects.fileCollection()
val implementationSourcePaths = run {
    val baseDirectory = rootDir
    implementationSources.elements.map { locations ->
        locations.map { it.asFile.relativeTo(baseDirectory).invariantSeparatorsPath }.sorted()
    }
}

gradle.projectsEvaluated {
    val mainSourceSets = subprojects.flatMap { module ->
        module.extensions.findByType<KotlinMultiplatformExtension>()
            ?.targets?.flatMap { target ->
                target.compilations.findByName("main")?.allKotlinSourceSets.orEmpty()
            }.orEmpty()
    }.distinct()
    implementationSources.from(mainSourceSets.filter { it.name != "specMain" }.map { it.kotlin })
}

detekt {
    toolVersion.set(libs.versions.detekt)
    config.setFrom(layout.projectDirectory.file("lint/detekt.yml"))
    disableDefaultRuleSets.set(true)
    allRules.set(false)
    failOnSeverity.set(FailOnSeverity.Warning)
}

tasks.withType<Detekt>().configureEach {
    pluginClasspath.setFrom(rulePlugins)
    inputs.property("specApiRuleLoading", "content-addressed-v1")
    // SourceDirectorySet-relative fingerprints alone miss unchanged files moved between modules.
    inputs.property("specApiSourcePaths", implementationSourcePaths)
    setSource(implementationSources)
    parallel.set(providers.gradleProperty("specApiParallel").map(String::toBoolean).orElse(false))
    basePath.set(rootDir.absolutePath)
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(true)
        sarif.outputLocation.set(layout.buildDirectory.file("reports/spec-api/$name.sarif"))
    }
}

tasks.register("checkSpecApi") {
    group = "verification"
    description = "Rejects ordinary exported declarations outside specMain."
    dependsOn(tasks.named("detekt"))
}

tasks.register<Detekt>("reportSpecApi") {
    description = "Reports existing spec API violations without failing; not an acceptance gate."
    ignoreFailures.set(true)
}
