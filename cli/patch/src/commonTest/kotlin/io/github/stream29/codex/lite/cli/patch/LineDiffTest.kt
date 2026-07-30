package io.github.stream29.codex.lite.cli.patch

import de.infix.testBalloon.framework.core.testSuite
import kotlin.random.Random
import kotlin.test.assertEquals

val lineDiffTest by testSuite {
    test("finds internal context between repeated edit groups") {
        val lines = diffPatchLines(
            oldLines = listOf("same", "old one", "middle", "old two", "same"),
            newLines = listOf("same", "new one", "middle", "new two", "same"),
        )

        assertEquals(
            listOf(
                "  same",
                "- old one",
                "+ new one",
                "  middle",
                "- old two",
                "+ new two",
                "  same",
            ),
            lines.map(PatchPresentationLine::text),
        )
    }

    test("compacts unchanged ranges around applied changes") {
        val lines = diffPatchLines(
            oldLines = (0..10).map(Int::toString),
            newLines = (0..10).map { line ->
                if (line == 5) "changed" else line.toString()
            },
        ).compactPatchContext(contextLineCount = 1)

        assertEquals(
            listOf(
                "  …",
                "  4",
                "- 5",
                "+ changed",
                "  6",
                "  …",
            ),
            lines.map(PatchPresentationLine::text),
        )
    }

    test("falls back without allocating an unbounded dynamic matrix") {
        val lines = diffPatchLines(
            oldLines = List(1_100) { index -> "old $index" },
            newLines = List(1_100) { index -> "new $index" },
        )

        assertEquals(2_200, lines.size)
        assertEquals(PatchPresentationLineKind.Removal, lines.first().kind)
        assertEquals(PatchPresentationLineKind.Addition, lines.last().kind)
    }

    test("produces a valid edit script for repeated and reordered lines") {
        val random = Random(29)
        repeat(200) {
            val oldLines = List(random.nextInt(30)) {
                "line ${random.nextInt(8)}"
            }
            val newLines = List(random.nextInt(30)) {
                "line ${random.nextInt(8)}"
            }

            assertTransforms(
                oldLines = oldLines,
                newLines = newLines,
                diff = diffPatchLines(oldLines, newLines),
            )
        }
    }
}

private fun assertTransforms(
    oldLines: List<String>,
    newLines: List<String>,
    diff: List<PatchPresentationLine>,
) {
    var oldIndex = 0
    val transformed = buildList {
        diff.forEach { line ->
            val content = line.text.drop(2)
            when (line.kind) {
                PatchPresentationLineKind.Context -> {
                    assertEquals(oldLines[oldIndex], content)
                    add(content)
                    oldIndex++
                }

                PatchPresentationLineKind.Removal -> {
                    assertEquals(oldLines[oldIndex], content)
                    oldIndex++
                }

                PatchPresentationLineKind.Addition -> add(content)
                PatchPresentationLineKind.Metadata,
                PatchPresentationLineKind.File,
                PatchPresentationLineKind.Failure,
                -> error("Unexpected non-diff line: $line")
            }
        }
    }

    assertEquals(oldLines.size, oldIndex)
    assertEquals(newLines, transformed)
}
