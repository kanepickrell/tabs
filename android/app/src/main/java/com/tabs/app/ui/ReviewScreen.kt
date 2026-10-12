package com.tabs.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabs.app.DeckOrder
import com.tabs.app.ReviewViewModel
import com.tabs.app.Screen
import com.tabs.app.Tab
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlin.math.roundToInt

private const val CARD_HEIGHT = 0.86f
private const val COMMIT_FRACTION = 0.24f
private const val FLING_PX_PER_SEC = 1000f
private val CARD_SHAPE = RoundedCornerShape(28.dp)
private val PEEK = 14.dp

@Composable
fun ReviewScreen(viewModel: ReviewViewModel) {
    val state = viewModel.state
    val context = LocalContext.current

    Scaffold { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
        ) {
            Header(
                remaining = state.tabs.size,
                closed = state.closedThisSession,
                kept = state.keptThisSession,
                onRefresh = {
                    if (state.screen == Screen.DECK) viewModel.refresh() else viewModel.loadArchive()
                },
            )

            if (state.configured) {
                ScreenTabs(
                    current = state.screen,
                    archiveCount = state.archive.size,
                    onPick = viewModel::show,
                )
            }

            if (state.configured && state.screen == Screen.DECK) {
                OrderChips(current = state.order, onPick = viewModel::setOrder)
            }

            if (state.loading || state.archiveLoading) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            state.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            when {
                !state.configured -> Notice(NOT_CONFIGURED)

                state.screen == Screen.ARCHIVE -> ArchiveList(
                    archive = state.archive,
                    onOpen = { entry ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
                    },
                    onForget = viewModel::forget,
                )

                state.tabs.isEmpty() && !state.loading -> Notice(ALL_DONE)
                else -> Deck(
                    tabs = state.tabs,
                    siblingsOf = viewModel::siblingsOf,
                    onClose = viewModel::close,
                    onKeep = viewModel::keep,
                    onOpen = { tab ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tab.url)))
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(remaining: Int, closed: Int, kept: Int, onRefresh: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (remaining == 0) "Tabs" else "$remaining left",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            val tally = listOfNotNull(
                closed.takeIf { it > 0 }?.let { "$it closed" },
                kept.takeIf { it > 0 }?.let { "$it kept" },
            ).joinToString(" · ")
            if (tally.isNotEmpty()) {
                Text(
                    tally,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun ScreenTabs(current: Screen, archiveCount: Int, onPick: (Screen) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTab("Deck", current == Screen.DECK) { onPick(Screen.DECK) }
        ScreenTab(
            if (archiveCount > 0) "Closed ($archiveCount)" else "Closed",
            current == Screen.ARCHIVE,
        ) { onPick(Screen.ARCHIVE) }
    }
}

@Composable
private fun ScreenTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .padding(top = 5.dp)
                .height(2.dp)
                .width(if (selected) 26.dp else 0.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun OrderChips(current: DeckOrder, onPick: (DeckOrder) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DeckOrder.entries.forEach { order ->
            FilterChip(
                selected = order == current,
                onClick = { onPick(order) },
                label = { Text(order.label, style = MaterialTheme.typography.labelLarge) },
                shape = CircleShape,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = order == current,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

/**
 * The pile. Only the top three are composed; anything deeper is hidden behind
 * them anyway.
 *
 * [drag] is the top card's live horizontal offset in pixels, owned here so the
 * cards underneath can grow toward full size as the top one leaves. Every
 * visual read of it happens inside a `graphicsLayer`/`offset`/draw lambda, so a
 * drag re-runs layout and draw without ever recomposing.
 */
@Composable
private fun Deck(
    tabs: List<Tab>,
    siblingsOf: (Tab) -> Int,
    onClose: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onOpen: (Tab) -> Unit,
) {
    val drag = remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val commitAt = widthPx * COMMIT_FRACTION

            // Back to front, so the top card composes last and wins the touches.
            val visible = tabs.take(3).reversed()
            visible.forEachIndexed { index, tab ->
                val depth = visible.lastIndex - index
                key(tab.hash) {
                    if (depth == 0) {
                        TopCard(
                            tab = tab,
                            siblings = siblingsOf(tab),
                            widthPx = widthPx,
                            commitAt = commitAt,
                            drag = drag,
                            onClose = onClose,
                            onKeep = onKeep,
                            onOpen = onOpen,
                        )
                    } else {
                        BackCard(tab, depth, commitAt, drag)
                    }
                }
            }
        }
        Legend()
    }
}

@Composable
private fun TopCard(
    tab: Tab,
    siblings: Int,
    widthPx: Float,
    commitAt: Float,
    drag: MutableFloatState,
    onClose: (Tab) -> Unit,
    onKeep: (Tab) -> Unit,
    onOpen: (Tab) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Velocity as a smoothed px/sec, tracked by hand. A short decisive flick
    // should throw the card even if the finger never travels far, which is the
    // difference between a stack that feels physical and one that feels sticky.
    val velocity = remember(tab.hash) { mutableFloatStateOf(0f) }
    val lastEventAt = remember(tab.hash) { mutableFloatStateOf(0f) }

    fun release(target: Float, commit: (() -> Unit)?) {
        scope.launch {
            val from = drag.floatValue
            val animation = Animatable(from)
            if (commit == null) {
                animation.animateTo(
                    0f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                ) { drag.floatValue = value }
            } else {
                animation.animateTo(target, tween(durationMillis = 220)) { drag.floatValue = value }
                // Zero it before the pile changes, or the new top card inherits
                // the outgoing card's offset for a frame.
                drag.floatValue = 0f
                commit()
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(CARD_HEIGHT)
            .offset { IntOffset(drag.floatValue.roundToInt(), 0) }
            .graphicsLayer {
                val ratio = (drag.floatValue / widthPx).coerceIn(-1f, 1f)
                cameraDistance = 14f * density
                rotationZ = ratio * 9f
                rotationY = ratio * 13f // the 3D tilt, not just a flat spin
                rotationX = -abs(ratio) * 3f
                val lift = min(1f, abs(drag.floatValue) / commitAt)
                scaleX = 1f - 0.015f * lift
                scaleY = 1f - 0.015f * lift
            }
            // Without this, starting a rightward drag near the left edge is
            // eaten by the system back gesture and kicks you out of the app.
            // Android caps exclusion at 200dp per edge, so this helps more than
            // it cures; the card's side margin does the rest.
            .systemGestureExclusion()
            // Tap and drag share one pointerInput so the drag consumes events
            // before the tap detector ever sees a release.
            .pointerInput(tab.hash) {
                coroutineScope {
                    launch { detectTapGestures(onTap = { onOpen(tab) }) }
                    launch {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                velocity.floatValue = 0f
                                lastEventAt.floatValue = 0f
                            },
                            onDragEnd = {
                                val offset = drag.floatValue
                                val speed = velocity.floatValue
                                val throwLeft = offset <= -commitAt ||
                                    (speed <= -FLING_PX_PER_SEC && offset < 0f)
                                val throwRight = offset >= commitAt ||
                                    (speed >= FLING_PX_PER_SEC && offset > 0f)
                                when {
                                    throwLeft -> release(-widthPx * 1.6f) { onClose(tab) }
                                    throwRight -> release(widthPx * 1.6f) { onKeep(tab) }
                                    else -> release(0f, null)
                                }
                            },
                            onDragCancel = { release(0f, null) },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                drag.floatValue += delta
                                val now = change.uptimeMillis.toFloat()
                                val dt = now - lastEventAt.floatValue
                                if (lastEventAt.floatValue != 0f && dt > 0f) {
                                    val instant = delta / dt * 1000f
                                    velocity.floatValue = velocity.floatValue * 0.6f + instant * 0.4f
                                }
                                lastEventAt.floatValue = now
                            },
                        )
                    }
                }
            }
    ) {
        CardFace(tab, siblings, elevation = 26.dp, glare = drag, widthPx = widthPx)
        Stamp(drag, commitAt, left = true)
        Stamp(drag, commitAt, left = false)
    }
}

@Composable
private fun BackCard(tab: Tab, depth: Int, commitAt: Float, drag: MutableFloatState) {
    val peekPx = with(LocalDensity.current) { PEEK.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(CARD_HEIGHT)
            .graphicsLayer {
                // As the top card is dragged toward its commit point, every card
                // below rises one step: the next one grows to full size and
                // slides up into place.
                val advance = min(1f, abs(drag.floatValue) / commitAt)
                val effective = depth - advance
                scaleX = 1f - 0.045f * effective
                scaleY = 1f - 0.045f * effective
                translationY = peekPx * effective
                alpha = 1f - 0.12f * effective
            }
    ) {
        CardFace(tab, siblings = 0, elevation = (10 - depth * 3).coerceAtLeast(2).dp)
    }
}

@Composable
private fun CardFace(
    tab: Tab,
    siblings: Int,
    elevation: androidx.compose.ui.unit.Dp,
    glare: MutableFloatState? = null,
    widthPx: Float = 1f,
) {
    Box(
        Modifier
            .fillMaxSize()
            // A real cast shadow, then the surface, then a hairline. Material's
            // flat tonal elevation alone leaves the card looking printed on.
            .shadow(elevation, CARD_SHAPE, clip = false)
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CARD_SHAPE)
            .then(
                if (glare == null) Modifier else Modifier.drawWithContent {
                    drawContent()
                    // A band of light that sweeps across the card as it tilts.
                    val ratio = (glare.floatValue / widthPx).coerceIn(-1f, 1f)
                    if (abs(ratio) < 0.01f) return@drawWithContent
                    val centre = size.width * (0.5f - ratio * 0.8f)
                    val half = size.width * 0.42f
                    drawRect(
                        brush = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.09f * abs(ratio)),
                            1f to Color.Transparent,
                            start = Offset(centre - half, 0f),
                            end = Offset(centre + half, size.height),
                        )
                    )
                }
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Monogram(tab.host)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tab.host,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            ageLabel(tab.ageDays),
                            tab.brief?.readingMinutes?.takeIf { it > 0 }?.let { "$it min read" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.size(22.dp))

            Text(
                tab.brief?.shortTitle?.takeIf { it.isNotBlank() } ?: tab.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                lineHeight = 38.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )

            tab.brief?.let { brief ->
                if (brief.oneLiner.isNotBlank()) {
                    Spacer(Modifier.size(18.dp))
                    Text(
                        brief.oneLiner,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 25.sp,
                        // Six lines holds a 22-word sentence at this size with
                        // room for the longer ones written before the cap.
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            tab.brief?.question?.takeIf { it.isNotBlank() }?.let { question ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            "BEFORE YOU READ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            question,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
            }

            if (siblings > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) {
                    Text(
                        "$siblings more from ${tab.host}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tap to read",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    tab.deviceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** DONE / KEEP, stamped on at an angle, fading in with drag distance. */
@Composable
private fun Stamp(drag: MutableFloatState, commitAt: Float, left: Boolean) {
    val colour = if (left) CloseRed else KeepGreen
    Box(
        Modifier
            .fillMaxSize()
            .padding(26.dp),
        contentAlignment = if (left) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val offset = drag.floatValue
                    val pointingRight = offset > 0f
                    val show = if (left) !pointingRight else pointingRight
                    val progress = min(1f, abs(offset) / commitAt)
                    alpha = if (show) progress else 0f
                    rotationZ = if (left) 14f else -14f
                    val pop = 0.85f + 0.15f * progress
                    scaleX = pop
                    scaleY = pop
                }
                .border(3.dp, colour, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                if (left) "DONE" else "KEEP",
                color = colour,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleMedium,
                letterSpacing = 3.sp,
            )
        }
    }
}

@Composable
internal fun Monogram(host: String) {
    val letter = host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '*'
    // Stable per-site colour, so a site you see often becomes recognisable
    // before you have read a word of the card.
    val hue = (host.hashCode().absoluteValue % 360).toFloat()
    val tint = Color.hsl(hue, 0.42f, 0.58f)
    Box(
        Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.toString(),
            color = tint,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun Legend() = Row(
    Modifier
        .fillMaxWidth()
        .padding(horizontal = 30.dp, vertical = 18.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text(
        "← done, close it",
        style = MaterialTheme.typography.labelMedium,
        color = CloseRed.copy(alpha = 0.85f),
    )
    Text(
        "keep it →",
        style = MaterialTheme.typography.labelMedium,
        color = KeepGreen.copy(alpha = 0.85f),
    )
}

@Composable
private fun Notice(text: String) = Box(
    Modifier
        .fillMaxSize()
        .padding(32.dp),
    contentAlignment = Alignment.Center,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun ageLabel(days: Long) = when {
    days <= 0L -> "opened today"
    days == 1L -> "open 1 day"
    days < 30L -> "open $days days"
    days < 365L -> "open ${days / 30} months"
    else -> "open ${days / 365} years"
}

private const val NOT_CONFIGURED =
    "Not configured.\n\nPut TABS_DB_URL and TABS_SPACE_ID in android/local.properties " +
        "(copy them from the Chrome extension settings page) and rebuild."

private const val ALL_DONE =
    "Nothing left to read.\n\nThat is the whole stack — hit Refresh if you have opened more since."
