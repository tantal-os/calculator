package app.tantal.calculator

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class HistoryReveal(
    initiallyOpen: Boolean,
    private val scope: CoroutineScope,
    private val animation: AnimationSpec<Float>,
    private val flingThreshold: Float,
    private val onTargetChanged: (Boolean) -> Unit,
) : NestedScrollConnection {
    var height = 0
    var fraction by mutableFloatStateOf(if (initiallyOpen) 1f else 0f)
        private set
    private var settling: Job? = null
    private var listDragging = false

    fun drag(delta: Float): Float {
        if (height <= 0) return 0f
        settling?.cancel()
        val previous = fraction
        fraction = (fraction + delta / height).coerceIn(0f, 1f)
        return ((fraction - previous) * height).coerceIn(minOf(0f, delta), maxOf(0f, delta))
    }

    fun settle(velocity: Float) {
        val open = when {
            velocity >= flingThreshold -> true
            velocity <= -flingThreshold -> false
            else -> fraction >= 0.5f
        }
        moveTo(open, velocity)
    }

    fun moveTo(open: Boolean, velocity: Float = 0f) {
        listDragging = false
        onTargetChanged(open)
        settling?.cancel()
        settling = scope.launch {
            animate(fraction, if (open) 1f else 0f, velocity / height.coerceAtLeast(1), animation) { value, _ ->
                fraction = value.coerceIn(0f, 1f)
            }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || !listDragging) return Offset.Zero
        return Offset(0f, drag(available.y))
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        val moved = drag(available.y)
        if (moved != 0f) listDragging = true
        return Offset(0f, moved)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!listDragging) return Velocity.Zero
        settle(available.y)
        return Velocity(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (available.y > -flingThreshold || fraction != 1f) return Velocity.Zero
        moveTo(false, available.y)
        return Velocity(0f, available.y)
    }
}
