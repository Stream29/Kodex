package io.github.stream29.kodex.integrationtest

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.sun.management.ThreadMXBean
import io.github.stream29.kodex.cli.patch.PatchPresentationLine
import io.github.stream29.kodex.cli.patch.PatchPresentationLineKind
import io.github.stream29.kodex.cli.patch.PendingPatchToolEventView
import io.github.stream29.kodex.cli.patch.toPendingPatchPresentation
import io.github.stream29.kodex.utils.applypatch.AddFileHunk
import io.github.stream29.kodex.utils.applypatch.Patch
import io.github.stream29.kodex.utils.applypatch.UpdateFileChunk
import io.github.stream29.kodex.utils.applypatch.UpdateFileHunk
import io.github.stream29.kodex.utils.applypatch.parsePatch
import io.github.stream29.kodex.utils.terminaltext.TerminalCellSegment
import io.github.stream29.kodex.utils.terminaltext.terminalCellSegments
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.test.runTest
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val ProbeEnabledEnvironmentVariable: String = "KODEX_PATCH_RENDERER_PERFORMANCE_PROBE"
private const val ProbeRepetitionsEnvironmentVariable: String = "KODEX_PATCH_RENDERER_PROBE_REPETITIONS"
private const val ProbeTerminalWidth: Int = 80
private const val ProbeVisibleContentLineCount: Int = 199
private const val BytesPerMebibyte: Double = 1024.0 * 1024.0

/**
 * Manual full-pipeline probe.
 *
 * Run only this probe with:
 *
 * `KODEX_PATCH_RENDERER_PERFORMANCE_PROBE=1 ./gradlew :integration-test:jvmTest
 * --tests io.github.stream29.kodex.integrationtest.PatchRendererPerformanceProbeTest`
 */
class PatchRendererPerformanceProbeTest {
    @Test
    fun comparePatchParsingProjectionAndRenderingScaling() = runTest(timeout = 10.minutes) {
        if (System.getenv(ProbeEnabledEnvironmentVariable) != "1") {
            println("Set $ProbeEnabledEnvironmentVariable=1 to run the patch renderer performance probe.")
            return@runTest
        }

        val repetitions = System.getenv(ProbeRepetitionsEnvironmentVariable)
            ?.toIntOrNull()
            ?.coerceIn(1, 9)
            ?: 3
        val allocationMeter = ThreadAllocationMeter()

        warmUpProbe(allocationMeter)

        val parserSummaries = parserProbeSpecifications().map { specification ->
            summarizeParserProbe(
                specification = specification,
                repetitions = repetitions,
                allocationMeter = allocationMeter,
            )
        }
        val lineCountSpecifications = lineCountRendererSpecifications()
        val lineCountRendererSummaries = lineCountSpecifications.map { specification ->
            summarizeRendererProbe(
                specification = specification,
                repetitions = repetitions,
                allocationMeter = allocationMeter,
            )
        }
        val boundedProjectionPrototypeSummaries = lineCountSpecifications.map { specification ->
            summarizeBoundedProjectionPrototype(
                specification = specification,
                repetitions = repetitions,
                allocationMeter = allocationMeter,
            )
        }
        val lineShapeSpecifications = lineShapeRendererSpecifications()
        val lineShapeRendererSummaries = lineShapeSpecifications.map { specification ->
            summarizeRendererProbe(
                specification = specification,
                repetitions = repetitions,
                allocationMeter = allocationMeter,
            )
        }
        val linearWrapPrototypeSummaries = lineShapeSpecifications
            .filter { specification -> specification.sourceLineCount == 1 }
            .map { specification ->
                summarizeLinearWrapPrototype(
                    specification = specification,
                    expectedRenderedRowCount = lineShapeRendererSummaries
                        .single { summary -> summary.specification.name == specification.name }
                        .renderedRowCount,
                    repetitions = repetitions,
                    allocationMeter = allocationMeter,
                )
            }

        val report = buildProbeReport(
            repetitions = repetitions,
            allocationMeter = allocationMeter,
            parserSummaries = parserSummaries,
            lineCountRendererSummaries = lineCountRendererSummaries,
            lineShapeRendererSummaries = lineShapeRendererSummaries,
            linearWrapPrototypeSummaries = linearWrapPrototypeSummaries,
            boundedProjectionPrototypeSummaries = boundedProjectionPrototypeSummaries,
        )
        val reportPath = writeProbeReport(report)
        println(report)
        println("Patch renderer performance report: ${reportPath.toAbsolutePath()}")
    }
}

private suspend fun warmUpProbe(allocationMeter: ThreadAllocationMeter) {
    repeat(2) {
        val rawPatch = rawAddPatch(
            path = "warm-up.txt",
            lines = List(20) { "warm-up" },
        )
        rawPatch.parsePatch()
        runRendererProbe(
            specification = RendererProbeSpecification(
                name = "warm-up",
                sourceLineCount = 20,
                payloadCharacterCount = 160,
                patch = parsedAddPatch(
                    path = "warm-up.txt",
                    contents = "warm-up\n".repeat(20),
                ),
            ),
            allocationMeter = allocationMeter,
        )
    }
    collectProbeGarbage()
}

private fun parserProbeSpecifications(): List<ParserProbeSpecification> = buildList {
    listOf(250, 500, 1_000, 2_000, 4_000, 8_000).forEach { lineCount ->
        val lines = List(lineCount) { "x".repeat(32) }
        add(
            ParserProbeSpecification(
                name = "short-$lineCount",
                sourceLineCount = lineCount,
                payloadCharacterCount = lineCount * 32,
                rawPatch = rawAddPatch(path = "short-$lineCount.txt", lines = lines),
                prototypeKind = ParserPrototypeKind.AddFile,
            ),
        )
    }
    listOf(5_000, 10_000, 20_000, 40_000).forEach { payloadCharacterCount ->
        val splitLines = splitPayload(
            payloadCharacterCount = payloadCharacterCount,
        )
        add(
            ParserProbeSpecification(
                name = "split-$payloadCharacterCount",
                sourceLineCount = splitLines.size,
                payloadCharacterCount = payloadCharacterCount,
                rawPatch = rawAddPatch(path = "split-$payloadCharacterCount.txt", lines = splitLines),
                prototypeKind = ParserPrototypeKind.AddFile,
            ),
        )
        add(
            ParserProbeSpecification(
                name = "hard-$payloadCharacterCount",
                sourceLineCount = 1,
                payloadCharacterCount = payloadCharacterCount,
                rawPatch = rawAddPatch(
                    path = "hard-$payloadCharacterCount.txt",
                    lines = listOf("x".repeat(payloadCharacterCount)),
                ),
                prototypeKind = ParserPrototypeKind.AddFile,
            ),
        )
    }
    listOf(250, 500, 1_000, 2_000, 4_000).forEach { changePairCount ->
        add(
            ParserProbeSpecification(
                name = "update-pairs-$changePairCount",
                sourceLineCount = changePairCount * 2,
                payloadCharacterCount = changePairCount * 64,
                rawPatch = rawUpdatePatch(
                    path = "update-$changePairCount.txt",
                    changePairCount = changePairCount,
                ),
                prototypeKind = ParserPrototypeKind.UpdateFile,
            ),
        )
    }
}

private fun lineCountRendererSpecifications(): List<RendererProbeSpecification> =
    listOf(200, 2_000, 20_000, 100_000, 500_000).map { lineCount ->
        val line = "0123456789abcdef"
        RendererProbeSpecification(
            name = "lines-$lineCount",
            sourceLineCount = lineCount,
            payloadCharacterCount = lineCount * line.length,
            patch = parsedAddPatch(
                path = "lines-$lineCount.txt",
                contents = "$line\n".repeat(lineCount),
            ),
        )
    }

private fun lineShapeRendererSpecifications(): List<RendererProbeSpecification> = buildList {
    listOf(20_000, 40_000, 80_000).forEach { payloadCharacterCount ->
        val splitLines = splitPayload(
            payloadCharacterCount = payloadCharacterCount,
        )
        add(
            RendererProbeSpecification(
                name = "split-$payloadCharacterCount",
                sourceLineCount = splitLines.size,
                payloadCharacterCount = payloadCharacterCount,
                patch = parsedAddPatch(
                    path = "split-$payloadCharacterCount.txt",
                    contents = splitLines.joinToString(separator = "\n", postfix = "\n"),
                ),
            ),
        )
        add(
            RendererProbeSpecification(
                name = "hard-$payloadCharacterCount",
                sourceLineCount = 1,
                payloadCharacterCount = payloadCharacterCount,
                patch = parsedAddPatch(
                    path = "hard-$payloadCharacterCount.txt",
                    contents = "x".repeat(payloadCharacterCount) + "\n",
                ),
            ),
        )
    }
}

private fun rawAddPatch(
    path: String,
    lines: List<String>,
): String = buildString {
    appendLine("*** Begin Patch")
    appendLine("*** Add File: $path")
    lines.forEach { line ->
        append('+')
        appendLine(line)
    }
    append("*** End Patch")
}

private fun rawUpdatePatch(
    path: String,
    changePairCount: Int,
): String = buildString {
    appendLine("*** Begin Patch")
    appendLine("*** Update File: $path")
    appendLine("@@")
    repeat(changePairCount) {
        append('-')
        appendLine("o".repeat(32))
        append('+')
        appendLine("n".repeat(32))
    }
    append("*** End Patch")
}

private fun parsedAddPatch(
    path: String,
    contents: String,
): Patch = Patch(
    patch = "",
    hunks = listOf(
        AddFileHunk(
            path = path,
            contents = contents,
        ),
    ),
)

private fun splitPayload(payloadCharacterCount: Int): List<String> {
    val lineCount = ProbeVisibleContentLineCount
    require(payloadCharacterCount >= lineCount)
    val minimumLineLength = payloadCharacterCount / lineCount
    val longerLineCount = payloadCharacterCount % lineCount
    return List(lineCount) { index ->
        "x".repeat(minimumLineLength + if (index < longerLineCount) 1 else 0)
    }
}

private fun summarizeParserProbe(
    specification: ParserProbeSpecification,
    repetitions: Int,
    allocationMeter: ThreadAllocationMeter,
): ParserProbeSummary {
    var expected: Patch? = null
    val productionSamples = List(repetitions) {
        collectProbeGarbage()
        val measured = measurePhase(allocationMeter) {
            val parsed = specification.rawPatch.parsePatch()
            assertEquals(1, parsed.hunks.size)
            parsed
        }
        expected?.let { expectedPatch ->
            assertEquals(expectedPatch, measured.value)
        } ?: run {
            expected = measured.value
        }
        measured.sample
    }
    val linearPrototypeSamples = List(repetitions) {
        collectProbeGarbage()
        val measured = measurePhase(allocationMeter) {
            when (specification.prototypeKind) {
                ParserPrototypeKind.AddFile -> parseGeneratedAddPatchLinearly(specification.rawPatch)
                ParserPrototypeKind.UpdateFile -> parseGeneratedUpdatePatchLinearly(specification.rawPatch)
            }
        }
        assertEquals(expected, measured.value)
        measured.sample
    }
    return ParserProbeSummary(
        specification = specification,
        production = productionSamples.summarize(),
        linearPrototype = linearPrototypeSamples.summarize(),
    )
}

private fun parseGeneratedUpdatePatchLinearly(rawPatch: String): Patch {
    val normalizedPatch = rawPatch.trim()
    var path: String? = null
    var hasStarted = false
    var hasChunk = false
    var hasEnded = false
    val oldLines = mutableListOf<String>()
    val newLines = mutableListOf<String>()
    normalizedPatch.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            !hasStarted -> {
                require(trimmed == "*** Begin Patch")
                hasStarted = true
            }
            trimmed == "*** End Patch" -> {
                hasEnded = true
            }
            line.startsWith("*** Update File: ") -> {
                require(path == null)
                path = line.removePrefix("*** Update File: ")
            }
            line == "@@" -> {
                require(path != null)
                require(!hasChunk)
                hasChunk = true
            }
            line.startsWith('-') -> {
                require(hasChunk)
                oldLines += line.substring(1)
            }
            line.startsWith('+') -> {
                require(hasChunk)
                newLines += line.substring(1)
            }
            else -> error("Unsupported generated update line: '$line'")
        }
    }
    require(hasStarted)
    require(hasChunk)
    require(hasEnded)
    return Patch(
        patch = normalizedPatch,
        hunks = listOf(
            UpdateFileHunk(
                path = requireNotNull(path),
                chunks = listOf(
                    UpdateFileChunk(
                        oldLines = oldLines,
                        newLines = newLines,
                    ),
                ),
            ),
        ),
    )
}

private fun parseGeneratedAddPatchLinearly(rawPatch: String): Patch {
    val normalizedPatch = rawPatch.trim()
    var path: String? = null
    var hasStarted = false
    var hasEnded = false
    val contents = StringBuilder()
    normalizedPatch.lineSequence().forEach { line ->
        val trimmed = line.trim()
        when {
            !hasStarted -> {
                require(trimmed == "*** Begin Patch")
                hasStarted = true
            }
            trimmed == "*** End Patch" -> {
                hasEnded = true
            }
            line.startsWith("*** Add File: ") -> {
                require(path == null)
                path = line.removePrefix("*** Add File: ")
            }
            else -> {
                require(!hasEnded)
                require(path != null)
                require(line.startsWith('+'))
                contents.append(line, 1, line.length)
                contents.append('\n')
            }
        }
    }
    require(hasStarted)
    require(hasEnded)
    return Patch(
        patch = normalizedPatch,
        hunks = listOf(
            AddFileHunk(
                path = requireNotNull(path),
                contents = contents.toString(),
            ),
        ),
    )
}

private suspend fun summarizeRendererProbe(
    specification: RendererProbeSpecification,
    repetitions: Int,
    allocationMeter: ThreadAllocationMeter,
): RendererProbeSummary {
    val runs = List(repetitions) {
        collectProbeGarbage()
        runRendererProbe(
            specification = specification,
            allocationMeter = allocationMeter,
        )
    }
    val projectedLineCounts = runs.map(RendererProbeRun::projectedLineCount).distinct()
    val renderedRowCounts = runs.map(RendererProbeRun::renderedRowCount).distinct()
    assertEquals(1, projectedLineCounts.size)
    assertEquals(1, renderedRowCounts.size)
    return RendererProbeSummary(
        specification = specification,
        projectedLineCount = projectedLineCounts.single(),
        renderedRowCount = renderedRowCounts.single(),
        projection = runs.map(RendererProbeRun::projection).summarize(),
        collapsedComposition = runs.map(RendererProbeRun::collapsedComposition).summarize(),
        toolExpansion = runs.map(RendererProbeRun::toolExpansion).summarize(),
        changesExpansion = runs.map(RendererProbeRun::changesExpansion).summarize(),
        terminalEmission = runs.map(RendererProbeRun::terminalEmission).summarize(),
    )
}

private suspend fun runRendererProbe(
    specification: RendererProbeSpecification,
    allocationMeter: ThreadAllocationMeter,
): RendererProbeRun {
    val projection = measurePhase(allocationMeter) {
        specification.patch.toPendingPatchPresentation().lines.size
    }
    assertEquals(specification.sourceLineCount + 1, projection.value)
    collectProbeGarbage()

    lateinit var collapsedComposition: PhaseSample
    lateinit var toolExpansion: PhaseSample
    lateinit var changesExpansion: PhaseSample
    lateinit var terminalEmission: PhaseSample
    var renderedRowCount = 0

    runMosaicTest(MosaicSnapshots) {
        collapsedComposition = measurePhase(allocationMeter) {
            setContentAndSnapshot {
                Box(Modifier.width(ProbeTerminalWidth)) {
                    PendingPatchToolEventView(specification.patch)
                }
            }
        }.sample
        toolExpansion = measurePhase(allocationMeter) {
            click()
        }.sample
        val expandedChanges = measurePhase(allocationMeter) {
            click(y = 2)
        }
        changesExpansion = expandedChanges.sample
        val emitted = measurePhase(allocationMeter) {
            expandedChanges.value.draw().render(
                ansiLevel = AnsiLevel.NONE,
                supportsKittyUnderlines = false,
            )
        }
        terminalEmission = emitted.sample
        renderedRowCount = emitted.value.lineSequence().count()
        assertTrue(renderedRowCount >= 4)
    }

    return RendererProbeRun(
        projectedLineCount = projection.value,
        renderedRowCount = renderedRowCount,
        projection = projection.sample,
        collapsedComposition = collapsedComposition,
        toolExpansion = toolExpansion,
        changesExpansion = changesExpansion,
        terminalEmission = terminalEmission,
    )
}

private fun summarizeLinearWrapPrototype(
    specification: RendererProbeSpecification,
    expectedRenderedRowCount: Int,
    repetitions: Int,
    allocationMeter: ThreadAllocationMeter,
): LinearWrapPrototypeSummary {
    val hunk = specification.patch.hunks.single() as AddFileHunk
    val sourceLine = "+ ${hunk.contents.removeSuffix("\n")}"
    var expectedWrappedLineCount: Int? = null
    val samples = List(repetitions) {
        collectProbeGarbage()
        val measured = measurePhase(allocationMeter) {
            sourceLine.wrapPatchHardLineLinearly(
                width = ProbeTerminalWidth,
                continuationPrefix = "  ",
            )
        }
        assertEquals(
            sourceLine,
            measured.value
                .mapIndexed { index, line -> if (index == 0) line else line.removePrefix("  ") }
                .joinToString(separator = ""),
        )
        expectedWrappedLineCount?.let { lineCount ->
            assertEquals(lineCount, measured.value.size)
        } ?: run {
            expectedWrappedLineCount = measured.value.size
        }
        measured.sample
    }
    val wrappedLineCount = requireNotNull(expectedWrappedLineCount)
    assertEquals(expectedRenderedRowCount, wrappedLineCount + 4)
    return LinearWrapPrototypeSummary(
        specification = specification,
        wrappedLineCount = wrappedLineCount,
        wrapping = samples.summarize(),
    )
}

private fun summarizeBoundedProjectionPrototype(
    specification: RendererProbeSpecification,
    repetitions: Int,
    allocationMeter: ThreadAllocationMeter,
): BoundedProjectionPrototypeSummary {
    var expectedLineCount: Int? = null
    val samples = List(repetitions) {
        collectProbeGarbage()
        val measured = measurePhase(allocationMeter) {
            specification.patch.toFirstAddFilePresentationPage(
                maximumLineCount = ProbeVisibleContentLineCount + 1,
            )
        }
        assertEquals(
            minOf(specification.sourceLineCount + 1, ProbeVisibleContentLineCount + 1),
            measured.value.size,
        )
        assertTrue(measured.value.isNotEmpty())
        assertEquals(PatchPresentationLineKind.File, measured.value.first().kind)
        expectedLineCount?.let { lineCount ->
            assertEquals(lineCount, measured.value.size)
        } ?: run {
            expectedLineCount = measured.value.size
        }
        measured.sample
    }
    return BoundedProjectionPrototypeSummary(
        specification = specification,
        projectedLineCount = requireNotNull(expectedLineCount),
        projection = samples.summarize(),
    )
}

private fun Patch.toFirstAddFilePresentationPage(
    maximumLineCount: Int,
): List<PatchPresentationLine> {
    require(maximumLineCount > 0)
    val hunk = hunks.single() as AddFileHunk
    return buildList {
        add(PatchPresentationLine("A ${hunk.path}", PatchPresentationLineKind.File))
        hunk.contents.lineSequence()
            .take(maximumLineCount - 1)
            .forEach { line ->
                add(PatchPresentationLine("+ $line", PatchPresentationLineKind.Addition))
            }
    }
}

private fun String.wrapPatchHardLineLinearly(
    width: Int,
    continuationPrefix: String,
): List<String> {
    require(width > 0)
    if (isEmpty()) return listOf("")

    val segments = terminalCellSegments()
    return buildList {
        var segmentIndex = 0
        var firstLine = true
        while (segmentIndex < segments.size) {
            var prefix = if (firstLine) "" else continuationPrefix
            var availableWidth = width - prefix.terminalCellWidth()
            var endSegmentIndex = segments.fittingEndIndex(
                startIndex = segmentIndex,
                maximumWidth = availableWidth,
            )
            if (endSegmentIndex == segmentIndex && prefix.isNotEmpty()) {
                prefix = ""
                availableWidth = width
                endSegmentIndex = segments.fittingEndIndex(
                    startIndex = segmentIndex,
                    maximumWidth = availableWidth,
                )
            }

            if (endSegmentIndex == segmentIndex) {
                add("${prefix}?")
                segmentIndex++
            } else {
                add(
                    prefix + substring(
                        startIndex = segments[segmentIndex].sourceStart,
                        endIndex = segments[endSegmentIndex - 1].sourceEnd,
                    ),
                )
                segmentIndex = endSegmentIndex
            }
            firstLine = false
        }
    }
}

private fun List<TerminalCellSegment>.fittingEndIndex(
    startIndex: Int,
    maximumWidth: Int,
): Int {
    if (maximumWidth <= 0) return startIndex

    var occupiedWidth = 0
    var index = startIndex
    while (index < size) {
        val nextWidth = this[index].cellWidth
        if (occupiedWidth + nextWidth > maximumWidth) {
            break
        }
        occupiedWidth += nextWidth
        index++
    }
    return index
}

private suspend fun <T> TestMosaic<T>.click(
    x: Int = 0,
    y: Int = 0,
): T {
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Press, MouseEvent.Button.Left))
    awaitSnapshot()
    sendMouseEvent(MouseEvent(x, y, MouseEvent.Type.Release))
    return awaitSnapshot()
}

private inline fun <T> measurePhase(
    allocationMeter: ThreadAllocationMeter,
    block: () -> T,
): MeasuredValue<T> {
    val threadId = Thread.currentThread().threadId()
    val allocatedBefore = allocationMeter.allocatedBytes(threadId)
    val start = System.nanoTime()
    val value = block()
    val elapsedNanoseconds = System.nanoTime() - start
    val finalThreadId = Thread.currentThread().threadId()
    val allocatedAfter = allocationMeter.allocatedBytes(finalThreadId)
    val allocatedBytes = if (
        threadId == finalThreadId &&
        allocatedBefore != null &&
        allocatedAfter != null
    ) {
        (allocatedAfter - allocatedBefore).coerceAtLeast(0)
    } else {
        null
    }
    return MeasuredValue(
        value = value,
        sample = PhaseSample(
            elapsedNanoseconds = elapsedNanoseconds,
            allocatedBytes = allocatedBytes,
        ),
    )
}

private fun collectProbeGarbage() {
    repeat(2) {
        System.gc()
        Thread.sleep(25)
    }
}

private class ThreadAllocationMeter {
    private val bean: ThreadMXBean? = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)
        ?.takeIf(ThreadMXBean::isThreadAllocatedMemorySupported)
        ?.also { threadBean ->
            if (!threadBean.isThreadAllocatedMemoryEnabled) {
                threadBean.isThreadAllocatedMemoryEnabled = true
            }
        }

    val isSupported: Boolean
        get() = bean != null

    fun allocatedBytes(threadId: Long): Long? =
        bean?.getThreadAllocatedBytes(threadId)?.takeIf { bytes -> bytes >= 0 }
}

private fun List<PhaseSample>.summarize(): PhaseSummary {
    require(isNotEmpty())
    val elapsed = map(PhaseSample::elapsedNanoseconds).sorted()
    val allocations = mapNotNull(PhaseSample::allocatedBytes).sorted()
    return PhaseSummary(
        medianNanoseconds = elapsed[elapsed.size / 2],
        minimumNanoseconds = elapsed.first(),
        maximumNanoseconds = elapsed.last(),
        medianAllocatedBytes = allocations.takeIf(List<Long>::isNotEmpty)
            ?.let { values -> values[values.size / 2] },
    )
}

private fun buildProbeReport(
    repetitions: Int,
    allocationMeter: ThreadAllocationMeter,
    parserSummaries: List<ParserProbeSummary>,
    lineCountRendererSummaries: List<RendererProbeSummary>,
    lineShapeRendererSummaries: List<RendererProbeSummary>,
    linearWrapPrototypeSummaries: List<LinearWrapPrototypeSummary>,
    boundedProjectionPrototypeSummaries: List<BoundedProjectionPrototypeSummary>,
): String = buildString {
    appendLine("# Patch renderer performance probe")
    appendLine()
    appendLine("- JVM: `${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}`")
    appendLine("- OS: `${System.getProperty("os.name")} ${System.getProperty("os.arch")}`")
    appendLine("- Processors: `${Runtime.getRuntime().availableProcessors()}`")
    appendLine("- Max heap: `${formatMebibytes(Runtime.getRuntime().maxMemory())} MiB`")
    appendLine("- Terminal width: `$ProbeTerminalWidth`")
    appendLine("- Repetitions: `$repetitions`; values are medians with min-max time ranges")
    appendLine("- Thread allocation measurement: `${if (allocationMeter.isSupported) "enabled" else "unavailable"}`")
    appendLine()
    appendLine("## Raw parser scaling")
    appendLine()
    appendLine(
        "| Scenario | Source lines | Payload chars | Raw chars " +
            "| Production parse ms/MiB | Linear generated-input prototype ms/MiB | Allocation reduction |",
    )
    appendLine("|---|---:|---:|---:|---:|---:|---:|")
    parserSummaries.forEach { summary ->
        appendLine(
            "| ${summary.specification.name} " +
                "| ${summary.specification.sourceLineCount} " +
                "| ${summary.specification.payloadCharacterCount} " +
                "| ${summary.specification.rawPatch.length} " +
                "| ${summary.production.formattedCombined()} " +
                "| ${summary.linearPrototype.formattedCombined()} " +
                "| ${summary.production.allocationReductionOver(summary.linearPrototype)} |",
        )
    }
    appendLine()
    appendLine("## Pre-parsed renderer scaling by line count")
    appendLine()
    appendRendererTable(lineCountRendererSummaries)
    appendLine()
    appendLine("## Bounded first-page projection prototype")
    appendLine()
    appendLine(
        "The add-file prototype projects at most one 200-presentation-line page directly from the parsed patch.",
    )
    appendLine()
    appendLine("| Scenario | Source lines | Projected lines | Prototype ms/MiB |")
    appendLine("|---|---:|---:|---:|")
    boundedProjectionPrototypeSummaries.forEach { summary ->
        appendLine(
            "| ${summary.specification.name} " +
                "| ${summary.specification.sourceLineCount} " +
                "| ${summary.projectedLineCount} " +
                "| ${summary.projection.formattedCombined()} |",
        )
    }
    appendLine()
    appendLine("## Pre-parsed renderer scaling by line shape")
    appendLine()
    appendLine(
        "Each `split-*` payload uses $ProbeVisibleContentLineCount source lines, so all content fits " +
            "the same 200-presentation-line page as the matching one-line `hard-*` payload.",
    )
    appendLine()
    appendRendererTable(lineShapeRendererSummaries)
    appendLine()
    appendLine("## Linear hard-wrap prototype")
    appendLine()
    appendLine(
        "The prototype segments each complete hard line once, then wraps it by source offsets. " +
            "It preserves the rendered row count of the production Mosaic path.",
    )
    appendLine()
    appendLine("| Scenario | Payload chars | Wrapped rows | Prototype ms/MiB |")
    appendLine("|---|---:|---:|---:|")
    linearWrapPrototypeSummaries.forEach { summary ->
        appendLine(
            "| ${summary.specification.name} " +
                "| ${summary.specification.payloadCharacterCount} " +
                "| ${summary.wrappedLineCount} " +
                "| ${summary.wrapping.formattedCombined()} |",
        )
    }
}

private fun StringBuilder.appendRendererTable(summaries: List<RendererProbeSummary>) {
    appendLine(
        "| Scenario | Source lines | Payload chars | Projected lines | Rendered rows " +
            "| Projection ms/MiB | Collapsed ms/MiB | Open tool ms/MiB " +
            "| Open changes ms/MiB | Emit ms/MiB |",
    )
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    summaries.forEach { summary ->
        appendLine(
            "| ${summary.specification.name} " +
                "| ${summary.specification.sourceLineCount} " +
                "| ${summary.specification.payloadCharacterCount} " +
                "| ${summary.projectedLineCount} " +
                "| ${summary.renderedRowCount} " +
                "| ${summary.projection.formattedCombined()} " +
                "| ${summary.collapsedComposition.formattedCombined()} " +
                "| ${summary.toolExpansion.formattedCombined()} " +
                "| ${summary.changesExpansion.formattedCombined()} " +
                "| ${summary.terminalEmission.formattedCombined()} |",
        )
    }
}

private fun PhaseSummary.formattedAllocation(): String =
    medianAllocatedBytes?.let(::formatMebibytes) ?: "n/a"

private fun PhaseSummary.formattedCombined(): String =
    "${formatMilliseconds(medianNanoseconds)}/${formattedAllocation()}"

private fun PhaseSummary.allocationReductionOver(other: PhaseSummary): String {
    val productionBytes = medianAllocatedBytes ?: return "n/a"
    val prototypeBytes = other.medianAllocatedBytes?.takeIf { bytes -> bytes > 0 } ?: return "n/a"
    return String.format(Locale.ROOT, "%.1fx", productionBytes.toDouble() / prototypeBytes)
}

private fun formatMilliseconds(nanoseconds: Long): String =
    String.format(Locale.ROOT, "%.3f", nanoseconds / 1_000_000.0)

private fun formatMebibytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.2f", bytes / BytesPerMebibyte)

private fun writeProbeReport(report: String): Path {
    val workingDirectory = Path.of(System.getProperty("user.dir"))
    val moduleDirectory = when {
        workingDirectory.fileName?.toString() == "integration-test" -> workingDirectory
        Files.isDirectory(workingDirectory.resolve("integration-test")) ->
            workingDirectory.resolve("integration-test")
        else -> workingDirectory
    }
    val reportPath = moduleDirectory.resolve("build/reports/patch-renderer-performance-probe.md")
    Files.createDirectories(reportPath.parent)
    Files.writeString(reportPath, report)
    return reportPath
}

private data class ParserProbeSpecification(
    val name: String,
    val sourceLineCount: Int,
    val payloadCharacterCount: Int,
    val rawPatch: String,
    val prototypeKind: ParserPrototypeKind,
)

private enum class ParserPrototypeKind {
    AddFile,
    UpdateFile,
}

private data class RendererProbeSpecification(
    val name: String,
    val sourceLineCount: Int,
    val payloadCharacterCount: Int,
    val patch: Patch,
)

private data class ParserProbeSummary(
    val specification: ParserProbeSpecification,
    val production: PhaseSummary,
    val linearPrototype: PhaseSummary,
)

private data class LinearWrapPrototypeSummary(
    val specification: RendererProbeSpecification,
    val wrappedLineCount: Int,
    val wrapping: PhaseSummary,
)

private data class BoundedProjectionPrototypeSummary(
    val specification: RendererProbeSpecification,
    val projectedLineCount: Int,
    val projection: PhaseSummary,
)

private data class RendererProbeRun(
    val projectedLineCount: Int,
    val renderedRowCount: Int,
    val projection: PhaseSample,
    val collapsedComposition: PhaseSample,
    val toolExpansion: PhaseSample,
    val changesExpansion: PhaseSample,
    val terminalEmission: PhaseSample,
)

private data class RendererProbeSummary(
    val specification: RendererProbeSpecification,
    val projectedLineCount: Int,
    val renderedRowCount: Int,
    val projection: PhaseSummary,
    val collapsedComposition: PhaseSummary,
    val toolExpansion: PhaseSummary,
    val changesExpansion: PhaseSummary,
    val terminalEmission: PhaseSummary,
)

private data class PhaseSample(
    val elapsedNanoseconds: Long,
    val allocatedBytes: Long?,
)

private data class PhaseSummary(
    val medianNanoseconds: Long,
    val minimumNanoseconds: Long,
    val maximumNanoseconds: Long,
    val medianAllocatedBytes: Long?,
)

private data class MeasuredValue<T>(
    val value: T,
    val sample: PhaseSample,
)
