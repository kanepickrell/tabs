package com.tabs.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabs.app.Archived

/**
 * Everything swiped left, kept forever. This screen is the whole reason the
 * left swipe is safe to make: without somewhere to see what was closed, the
 * guarantee is real but invisible, and an invisible guarantee does not make
 * anyone braver about closing a tab.
 */
@Composable
fun ArchiveList(
    archive: List<Archived>,
    onOpen: (Archived) -> Unit,
    onForget: (Archived) -> Unit,
) {
    var pendingForget by remember { mutableStateOf<Archived?>(null) }

    if (archive.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nothing closed yet.\n\nTabs you swipe left land here, and stay here. " +
                    "Closing one never loses the link.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(archive, key = { it.hash }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(entry) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Monogram(entry.host)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            entry.host,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            closedLabel(entry.closedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { pendingForget = entry }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "Forget",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    // Forgetting is the one thing in this app that cannot be undone, so it asks.
    pendingForget?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text("Forget this link?") },
            text = {
                Text(
                    "\"${entry.title}\"\n\nThe tab is already closed. This drops the " +
                        "saved URL too, and that cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onForget(entry)
                    pendingForget = null
                }) {
                    Text("Forget", color = CloseRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = null }) { Text("Keep it") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

private fun closedLabel(closedAt: Long): String {
    if (closedAt <= 0L) return "closed"
    val minutes = (System.currentTimeMillis() - closedAt) / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        minutes < 1440L -> "${minutes / 60L}h ago"
        else -> "${minutes / 1440L}d ago"
    }
}
