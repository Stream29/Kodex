package io.github.stream29.kodex.desktop.application

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.application.contract.ApplicationNavigationState
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.desktop.components.desktopSecondaryClick

/** One-row Desktop mapping of the TUI Session tab bar. */
@Composable
internal fun SessionTabBarDesktop(
    navigation: ApplicationNavigationState,
    onSelect: (Int) -> Unit,
    onCreate: () -> Unit,
    onOpenCatalog: () -> Unit,
    onClose: (SessionViewModel) -> Unit,
    onRename: (SessionViewModel) -> Unit,
    onDelete: (PersistedSessionViewModel) -> Unit,
): Unit {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RectangleShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TopBarButton(label = "Sessions", onClick = onOpenCatalog)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigation.tabs.forEachIndexed { index, target ->
                    key(target) {
                        SessionTabDesktop(
                            target = target,
                            selected = index == navigation.selectedIndex,
                            onSelect = { onSelect(index) },
                            onClose = { onClose(target) },
                            onRename = { onRename(target) },
                            onDelete = {
                                (target as? PersistedSessionViewModel)?.let(onDelete)
                            },
                        )
                    }
                }
                TopBarButton(label = "+", onClick = onCreate)
            }
        }
    }
}

@Composable
private fun TopBarButton(label: String, onClick: () -> Unit): Unit {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(label)
    }
}

@Composable
private fun SessionTabDesktop(
    target: SessionViewModel,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
): Unit {
    val name by target.name.collectAsState()
    val running = when (target) {
        is NewSessionViewModel -> false
        is PersistedSessionViewModel -> {
            val summary by target.summary.collectAsState()
            summary.rootRunning
        }
    }
    var menuOpen by remember(target) { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .desktopSecondaryClick { menuOpen = true }
                .clickable(onClick = onSelect),
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = name,
                    modifier = Modifier.widthIn(max = 180.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text("Close") },
                onClick = {
                    menuOpen = false
                    onClose()
                },
            )
            if (target is PersistedSessionViewModel) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}
