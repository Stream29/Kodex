package io.github.stream29.kodex.cli.components

import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val statusBarLayoutTest by testSuite {
    test("workingDirectoryLabelPreservesThePathTailAndCollapsesOnNarrowSurfaces") {
        val workingDirectory = Path("root", "projects", "very-long-project-directory", "workspace")

        assertEquals("cwd", workingDirectoryStatusLabel(workingDirectory, columns = 60))

        val regular = workingDirectoryStatusLabel(workingDirectory, columns = 80)
        assertTrue(regular.startsWith("…"), regular)
        assertTrue(regular.endsWith("workspace"), regular)
        assertTrue(regular.terminalCellWidth() <= 16, regular)

        val wide = workingDirectoryStatusLabel(workingDirectory, columns = 120)
        assertTrue(wide.startsWith("…"), wide)
        assertTrue(wide.endsWith("workspace"), wide)
        assertTrue(wide.terminalCellWidth() <= 28, wide)
    }

    test("statusBarPlanPinsSettingsAndWrapsWholeControls") {
        val width = 39
        val itemWidths = listOf(17, 10, 14, 5)

        val plan = statusBarLayoutPlan(
            width = width,
            itemWidths = itemWidths,
            settingsWidth = 10,
        )

        assertEquals(IntOffset(x = 29, y = 0), plan.settingsPosition)
        assertEquals(
            listOf(
                IntOffset(x = 0, y = 0),
                IntOffset(x = 18, y = 0),
                IntOffset(x = 0, y = 1),
                IntOffset(x = 15, y = 1),
            ),
            plan.itemPositions,
        )
        assertEquals(2, plan.rowCount)
        plan.itemPositions.zip(itemWidths).forEach { (position, itemWidth) ->
            if (position.y == 0) {
                assertTrue(position.x + itemWidth < plan.settingsPosition.x)
            } else {
                assertTrue(position.x + itemWidth <= width)
            }
        }
    }
}
