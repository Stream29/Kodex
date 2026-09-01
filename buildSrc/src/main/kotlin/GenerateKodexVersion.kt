import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GenerateKodexVersion : DefaultTask() {
    @get:Input
    abstract val applicationVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val version = applicationVersion.get()
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                package io.github.stream29.kodex.app.migration

                internal const val GeneratedKodexApplicationVersion: String = "$version"
                """.trimIndent() + "\n",
            )
        }
    }
}
