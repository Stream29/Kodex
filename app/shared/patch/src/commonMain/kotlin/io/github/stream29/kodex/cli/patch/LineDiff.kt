package io.github.stream29.kodex.cli.patch

private const val MaximumDynamicDiffCells: Long = 1_000_000

internal fun diffPatchLines(
    oldLines: List<String>,
    newLines: List<String>,
): List<PatchPresentationLine> = buildList {
    appendLineDiff(
        oldLines = oldLines,
        oldStart = 0,
        oldEnd = oldLines.size,
        newLines = newLines,
        newStart = 0,
        newEnd = newLines.size,
    )
}

internal fun List<PatchPresentationLine>.compactPatchContext(
    contextLineCount: Int = 3,
): List<PatchPresentationLine> {
    require(contextLineCount >= 0)

    val changedIndexes = indices.filter { index ->
        this[index].kind == PatchPresentationLineKind.Addition ||
            this[index].kind == PatchPresentationLineKind.Removal
    }
    if (changedIndexes.isEmpty()) return emptyList()

    val retained = BooleanArray(size)
    changedIndexes.forEach { changedIndex ->
        val start = (changedIndex - contextLineCount).coerceAtLeast(0)
        val end = (changedIndex + contextLineCount).coerceAtMost(lastIndex)
        for (index in start..end) {
            retained[index] = true
        }
    }

    return buildList {
        var index = 0
        while (index < this@compactPatchContext.size) {
            if (retained[index]) {
                add(this@compactPatchContext[index])
                index++
            } else {
                add(
                    PatchPresentationLine(
                        text = "  …",
                        kind = PatchPresentationLineKind.Context,
                    ),
                )
                while (index < this@compactPatchContext.size && !retained[index]) {
                    index++
                }
            }
        }
    }
}

private fun MutableList<PatchPresentationLine>.appendLineDiff(
    oldLines: List<String>,
    oldStart: Int,
    oldEnd: Int,
    newLines: List<String>,
    newStart: Int,
    newEnd: Int,
) {
    var oldPrefixEnd = oldStart
    var newPrefixEnd = newStart
    while (
        oldPrefixEnd < oldEnd &&
        newPrefixEnd < newEnd &&
        oldLines[oldPrefixEnd] == newLines[newPrefixEnd]
    ) {
        addContext(oldLines[oldPrefixEnd])
        oldPrefixEnd++
        newPrefixEnd++
    }

    var oldSuffixStart = oldEnd
    var newSuffixStart = newEnd
    while (
        oldSuffixStart > oldPrefixEnd &&
        newSuffixStart > newPrefixEnd &&
        oldLines[oldSuffixStart - 1] == newLines[newSuffixStart - 1]
    ) {
        oldSuffixStart--
        newSuffixStart--
    }

    when {
        oldPrefixEnd == oldSuffixStart -> {
            for (index in newPrefixEnd until newSuffixStart) {
                addAddition(newLines[index])
            }
        }

        newPrefixEnd == newSuffixStart -> {
            for (index in oldPrefixEnd until oldSuffixStart) {
                addRemoval(oldLines[index])
            }
        }

        else -> {
            val anchors = patienceAnchors(
                oldLines = oldLines,
                oldStart = oldPrefixEnd,
                oldEnd = oldSuffixStart,
                newLines = newLines,
                newStart = newPrefixEnd,
                newEnd = newSuffixStart,
            )
            if (anchors.isNotEmpty()) {
                var previousOld = oldPrefixEnd
                var previousNew = newPrefixEnd
                anchors.forEach { anchor ->
                    appendLineDiff(
                        oldLines = oldLines,
                        oldStart = previousOld,
                        oldEnd = anchor.oldIndex,
                        newLines = newLines,
                        newStart = previousNew,
                        newEnd = anchor.newIndex,
                    )
                    addContext(oldLines[anchor.oldIndex])
                    previousOld = anchor.oldIndex + 1
                    previousNew = anchor.newIndex + 1
                }
                appendLineDiff(
                    oldLines = oldLines,
                    oldStart = previousOld,
                    oldEnd = oldSuffixStart,
                    newLines = newLines,
                    newStart = previousNew,
                    newEnd = newSuffixStart,
                )
            } else {
                val oldSize = oldSuffixStart - oldPrefixEnd
                val newSize = newSuffixStart - newPrefixEnd
                if (oldSize.toLong() * newSize <= MaximumDynamicDiffCells) {
                    appendDynamicDiff(
                        oldLines = oldLines,
                        oldStart = oldPrefixEnd,
                        oldEnd = oldSuffixStart,
                        newLines = newLines,
                        newStart = newPrefixEnd,
                        newEnd = newSuffixStart,
                    )
                } else {
                    for (index in oldPrefixEnd until oldSuffixStart) {
                        addRemoval(oldLines[index])
                    }
                    for (index in newPrefixEnd until newSuffixStart) {
                        addAddition(newLines[index])
                    }
                }
            }
        }
    }

    for (index in oldSuffixStart until oldEnd) {
        addContext(oldLines[index])
    }
}

private fun MutableList<PatchPresentationLine>.appendDynamicDiff(
    oldLines: List<String>,
    oldStart: Int,
    oldEnd: Int,
    newLines: List<String>,
    newStart: Int,
    newEnd: Int,
) {
    val oldSize = oldEnd - oldStart
    val newSize = newEnd - newStart
    val longestCommonSubsequence = Array(oldSize + 1) {
        IntArray(newSize + 1)
    }

    for (oldOffset in oldSize - 1 downTo 0) {
        for (newOffset in newSize - 1 downTo 0) {
            longestCommonSubsequence[oldOffset][newOffset] =
                if (oldLines[oldStart + oldOffset] == newLines[newStart + newOffset]) {
                    longestCommonSubsequence[oldOffset + 1][newOffset + 1] + 1
                } else {
                    maxOf(
                        longestCommonSubsequence[oldOffset + 1][newOffset],
                        longestCommonSubsequence[oldOffset][newOffset + 1],
                    )
                }
        }
    }

    var oldOffset = 0
    var newOffset = 0
    while (oldOffset < oldSize && newOffset < newSize) {
        val oldLine = oldLines[oldStart + oldOffset]
        val newLine = newLines[newStart + newOffset]
        when {
            oldLine == newLine -> {
                addContext(oldLine)
                oldOffset++
                newOffset++
            }

            longestCommonSubsequence[oldOffset + 1][newOffset] >=
                longestCommonSubsequence[oldOffset][newOffset + 1] -> {
                addRemoval(oldLine)
                oldOffset++
            }

            else -> {
                addAddition(newLine)
                newOffset++
            }
        }
    }
    while (oldOffset < oldSize) {
        addRemoval(oldLines[oldStart + oldOffset])
        oldOffset++
    }
    while (newOffset < newSize) {
        addAddition(newLines[newStart + newOffset])
        newOffset++
    }
}

private data class LineAnchor(
    val oldIndex: Int,
    val newIndex: Int,
)

private fun patienceAnchors(
    oldLines: List<String>,
    oldStart: Int,
    oldEnd: Int,
    newLines: List<String>,
    newStart: Int,
    newEnd: Int,
): List<LineAnchor> {
    val oldCounts = mutableMapOf<String, Int>()
    for (index in oldStart until oldEnd) {
        val line = oldLines[index]
        oldCounts[line] = (oldCounts[line] ?: 0) + 1
    }

    val newCounts = mutableMapOf<String, Int>()
    val newIndexes = mutableMapOf<String, Int>()
    for (index in newStart until newEnd) {
        val line = newLines[index]
        newCounts[line] = (newCounts[line] ?: 0) + 1
        newIndexes[line] = index
    }

    val candidates = buildList {
        for (oldIndex in oldStart until oldEnd) {
            val line = oldLines[oldIndex]
            if (oldCounts[line] == 1 && newCounts[line] == 1) {
                add(
                    LineAnchor(
                        oldIndex = oldIndex,
                        newIndex = checkNotNull(newIndexes[line]),
                    ),
                )
            }
        }
    }
    if (candidates.isEmpty()) return emptyList()

    val previous = IntArray(candidates.size) { -1 }
    val tails = IntArray(candidates.size)
    var subsequenceSize = 0
    candidates.indices.forEach { candidateIndex ->
        var low = 0
        var high = subsequenceSize
        while (low < high) {
            val middle = (low + high) / 2
            if (candidates[tails[middle]].newIndex < candidates[candidateIndex].newIndex) {
                low = middle + 1
            } else {
                high = middle
            }
        }

        if (low > 0) {
            previous[candidateIndex] = tails[low - 1]
        }
        tails[low] = candidateIndex
        if (low == subsequenceSize) {
            subsequenceSize++
        }
    }

    val anchors = ArrayList<LineAnchor>(subsequenceSize)
    var candidateIndex = tails[subsequenceSize - 1]
    repeat(subsequenceSize) {
        anchors += candidates[candidateIndex]
        candidateIndex = previous[candidateIndex]
    }
    anchors.reverse()
    return anchors
}

private fun MutableList<PatchPresentationLine>.addContext(text: String) {
    add(PatchPresentationLine("  $text", PatchPresentationLineKind.Context))
}

private fun MutableList<PatchPresentationLine>.addAddition(text: String) {
    add(PatchPresentationLine("+ $text", PatchPresentationLineKind.Addition))
}

private fun MutableList<PatchPresentationLine>.addRemoval(text: String) {
    add(PatchPresentationLine("- $text", PatchPresentationLineKind.Removal))
}
