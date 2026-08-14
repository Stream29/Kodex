package io.github.stream29.kodex.desktop.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.Test

public class DesktopComponentsTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun composerRequestsInitialFocusWhenEnabled(): Unit =
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    DesktopComposer(
                        text = "",
                        cursorOffset = 0,
                        submitKey = DesktopComposerSubmitKey.Enter,
                        onValueChange = { _, _ -> },
                        onSubmit = {},
                        autoFocus = true,
                    )
                }
            }

            onNode(hasSetTextAction()).assertIsFocused()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun modalRendersInsideComposeScene(): Unit =
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    DesktopModal(
                        onDismissRequest = {},
                        modifier = Modifier.width(640.dp),
                    ) {
                        Surface(Modifier.fillMaxWidth().testTag("modal")) {
                            Text("Modal content")
                        }
                    }
                }
            }

            onNodeWithText("Modal content").assertExists()
            onNodeWithTag("modal").assertWidthIsEqualTo(640.dp)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun choiceRendersSelectionAndDispatchesUpdate(): Unit =
        runDesktopComposeUiTest {
            var selected = "balanced"
            setContent {
                MaterialTheme {
                    DesktopChoice(
                        label = "Reasoning",
                        selected = selected,
                        options = listOf("fast", "balanced"),
                        optionLabel = { it },
                        onSelect = { selected = it },
                    )
                }
            }

            onNodeWithText("Reasoning · balanced").performClick()
            onNodeWithText("fast").performClick()

            runOnIdle {
                assertEquals("fast", selected)
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun choiceGroupDispatchesSelectedOption(): Unit =
        runDesktopComposeUiTest {
            var selected = "Shift+Enter"
            setContent {
                MaterialTheme {
                    DesktopChoiceGroup(
                        selected = selected,
                        options = listOf("Shift+Enter", "Enter"),
                        optionLabel = { it },
                        onSelect = { selected = it },
                    )
                }
            }

            onNodeWithText("Enter").performClick()

            runOnIdle {
                assertEquals("Enter", selected)
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun messageExposesTitleAndDetail(): Unit =
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    DesktopMessage(
                        title = "No sessions",
                        detail = "Create a session to begin.",
                    )
                }
            }

            onNodeWithText("No sessions").assertExists()
            onNodeWithText("Create a session to begin.").assertExists()
        }
}
