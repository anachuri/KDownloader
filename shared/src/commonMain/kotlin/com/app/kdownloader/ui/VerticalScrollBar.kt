package com.app.kdownloader.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val TrackWidth = 4.dp
private val ThumbWidth = 6.dp
private val MinThumbHeight = 28.dp

/**
 * A slim, always-visible vertical scrollbar for a regular [ScrollState].
 *
 * Compose only ships a scrollbar for desktop/Skiko targets, and plain
 * `Modifier.verticalScroll` has no visual indicator on any platform, so this
 * lightweight overlay is shared by all of them. It is drawn on top of the
 * scrollable content and can be dragged to scroll.
 */
@Composable
fun VerticalScrollBar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val maxValue = scrollState.maxValue
    val viewport = scrollState.viewportSize
    if (maxValue <= 0 || viewport <= 0) return

    BoxWithConstraints(
        modifier = modifier.width(TrackWidth + ThumbWidth),
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeight = constraints.maxHeight.toFloat()
        if (trackHeight <= 0f) return@BoxWithConstraints

        val density = LocalDensity.current
        val contentHeight = (maxValue + viewport).toFloat()
        val minThumbHeight = with(density) { MinThumbHeight.toPx() }.coerceAtMost(trackHeight)
        val thumbHeight = (trackHeight * viewport / contentHeight).coerceIn(minThumbHeight, trackHeight)
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(TrackWidth)
                .clip(RoundedCornerShape(TrackWidth / 2f))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)),
        )

        Box(
            modifier = Modifier
                .offset {
                    val progress = if (maxValue > 0) scrollState.value.toFloat() / maxValue else 0f
                    IntOffset(0, (travel * progress).roundToInt())
                }
                .width(ThumbWidth)
                .height(with(density) { thumbHeight.toDp() })
                .clip(RoundedCornerShape(ThumbWidth / 2f))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                .pointerInput(maxValue, travel) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        if (travel > 0f) {
                            scrollState.dispatchRawDelta(dragAmount.y * maxValue / travel)
                        }
                    }
                },
        )
    }
}
