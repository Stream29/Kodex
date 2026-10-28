@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import dev.detekt.gradle.extensions.FailOnSeverity
import dev.detekt.gradle.plugin.DetektBasePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

@CacheableTransform
abstract class SpecApiRuleTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        input.copyTo(outputs.file(input.name))
    }
}

class SpecApiPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(DetektBasePlugin::class.java)
            val version = extensions.getByType(VersionCatalogsExtension::class.java)
                .named("libs").findVersion("detekt").get().requiredVersion
            val cli = configurations.create("specApiDetektCli") {
                isCanBeConsumed = false
                isCanBeResolved = true
            }
            dependencies.add(cli.name, "dev.detekt:detekt-cli:$version")
            dependencies.add("detektPlugins", "io.github.stream29.kodex:kodex-lint:1.0")
            dependencies.registerTransform(SpecApiRuleTransform::class.java) {
                from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "jar")
                to.attribute(ARTIFACT_TYPE_ATTRIBUTE, "spec-api-rule")
            }
            val rules = configurations.getByName("detektPlugins").incoming.artifactView {
                attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "spec-api-rule")
            }.files
            val settingsDir = layout.settingsDirectory
            extensions.configure(DetektExtension::class.java) {
                config.setFrom(settingsDir.file("lint/detekt.yml"))
                disableDefaultRuleSets.set(true)
                allRules.set(false)
                failOnSeverity.set(FailOnSeverity.Warning)
            }
            val sources = objects.fileCollection()
            val paths = sources.elements.map { locations ->
                locations.map { it.asFile.relativeTo(settingsDir.asFile).invariantSeparatorsPath }.sorted()
            }
            val report = listOf("checkSpecApiModuleAnalysis", "reportSpecApiModuleAnalysis").map { name ->
                tasks.register(name, Detekt::class.java) {
                    detektClasspath.setFrom(cli)
                    pluginClasspath.setFrom(rules)
                    inputs.property("specApiRuleLoading", "content-addressed-v1")
                    inputs.property("specApiSourcePaths", paths)
                    setSource(sources)
                    ignoreFailures.set(name.startsWith("report"))
                    parallel.set(providers.gradleProperty("specApiParallel").map(String::toBoolean).orElse(false))
                    basePath.set(settingsDir.asFile.absolutePath)
                    reports {
                        checkstyle.required.set(false)
                        checkstyle.outputLocation.set(layout.buildDirectory.file("reports/spec-api/$name.xml"))
                        html.required.set(false)
                        html.outputLocation.set(layout.buildDirectory.file("reports/spec-api/$name.html"))
                        markdown.required.set(false)
                        markdown.outputLocation.set(layout.buildDirectory.file("reports/spec-api/$name.md"))
                        sarif.required.set(true)
                        sarif.outputLocation.set(layout.buildDirectory.file("reports/spec-api/$name.sarif"))
                    }
                }
            }.last()
            tasks.register("normalizeSpecApiModuleReport", NormalizeSpecApiReport::class.java) {
                sourceFiles.from(sources)
                rawReport.from(report.flatMap { it.reports.sarif.outputLocation })
                dependsOn(report)
                output.set(layout.buildDirectory.file("reports/spec-api/module.sarif"))
            }
            val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
            sources.from(providers.provider {
                val main = kotlin.targets.flatMap { target ->
                    target.compilations.findByName("main")?.allKotlinSourceSets.orEmpty()
                }.distinct().filter { it.name != "specMain" }
                main.map { it.allKotlinSources }
            })
        }
    }
}

abstract class NormalizeSpecApiReport : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rawReport: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun normalize() {
        val destination = output.get().asFile
        destination.parentFile.mkdirs()
        if (sourceFiles.isEmpty) {
            destination.writeText("""{"version":"2.1.0","${'$'}schema":"https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json","runs":[]}""")
        } else {
            val report = rawReport.singleFile
            check(report.isFile) { "Missing Spec API report: $report" }
            report.copyTo(destination, overwrite = true)
        }
    }
}
