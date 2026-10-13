// SPDX-License-Identifier: GPL-3.0-only
package app.van.timetable.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.distinctUntilChanged

// A century on either side of the initial date; avoid giant pager indices whose
// layout estimates lose pixel precision when converted to Float.
internal const val DAY_PAGER_ANCHOR = 36_500
internal const val DAY_PAGER_COUNT = DAY_PAGER_ANCHOR * 2 + 1

// Inspired by ryanw-mobile/compose-pager-demo's page-offset graphicsLayer approach.
// Keep timetable text readable: use subtle scaling and fading, without 3D rotation.
@Composable
internal fun TimetablePager(
    state: PagerState,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state) {
        var previous = state.settledPage
        snapshotFlow { state.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page != previous)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                previous = page
            }
    }
    HorizontalPager(
        state = state,
        modifier = modifier,
        key = { it },
        flingBehavior =
            PagerDefaults.flingBehavior(
                state,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
                // A deliberate short drag should change weeks even without a fast fling.
                // Keep normal touch slop, finger tracking, and snap-on-release behavior.
                snapPositionalThreshold = 0.18f,
            ),
    ) { page ->
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                // Read the continuously changing offset during drawing, not composition.
                val offset =
                    ((state.currentPage - page) + state.currentPageOffsetFraction)
                        .absoluteValue
                        .coerceIn(0f, 1f)
                alpha = 1f - 0.12f * offset
                scaleX = 1f - 0.015f * offset
                scaleY = scaleX
            }
        ) {
            content(page)
        }
    }
}

// An oversized grid owns the entire gesture if it could pan at its start.
// Consume leftover movement/fling so reaching an edge does not also change weeks.
internal fun Modifier.gridPagingGuard(scroll: ScrollState): Modifier = composed {
    var gridOwnsGesture by remember { mutableStateOf(false) }
    val connection =
        remember(scroll) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset = if (gridOwnsGesture) Offset(available.x, 0f) else Offset.Zero

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity = if (gridOwnsGesture) Velocity(available.x, 0f) else Velocity.Zero
            }
        }
    nestedScroll(connection).pointerInput(scroll) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gridOwnsGesture = false
            var decided = false
            do {
                val change =
                    awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                        it.id == down.id
                    } ?: break
                val delta = change.position - change.previousPosition
                if (!decided && delta.x != 0f) {
                    gridOwnsGesture =
                        if (delta.x < 0) scroll.canScrollForward else scroll.canScrollBackward
                    decided = true
                }
            } while (change.pressed)
        }
    }
}
