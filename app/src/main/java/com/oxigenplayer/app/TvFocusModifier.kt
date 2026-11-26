// SPDX-License-Identifier: GPL-3.0-only
package com.oxigenplayer.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val FOCUS_ANIMATION_MS = 40

/**
 * Shared snapshot state for the [tvFocusable] modifier nodes. Focus changes are
 * consumed inside the layer/draw phases, so moving focus between cards never
 * recomposes the composables — only the affected layers are re-evaluated.
 */
@Suppress("AutoboxingStateCreation")
private class TvFocusableVisualState {
    // Snapshot state: reads inside the draw and graphics-layer phases are
    // observed automatically, so focus changes invalidate only those phases.
    var isFocused: Boolean by mutableStateOf(false)
    var focusProgress: Float by mutableStateOf(0f)
}

/**
 * TV-friendly focusable card modifier built on [Modifier.Node] (no `composed`).
 *
 * Focus visuals (scale, z-order, background tint and border) are driven by
 * node-internal snapshot state read inside the layer and draw phases, which
 * keeps D-pad navigation cheap even on large media grids and low-end TVs.
 */
@Composable
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    isCircle: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    isSelected: Boolean = false,
    scaleAmount: Float = 1.05f, // Subtle scale for fast focus movement.
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    semanticRole: Role? = null,
): Modifier {
    val lowRam = LocalLowRamMode.current
    val state = remember { TvFocusableVisualState() }
    return this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .semantics {
            (semanticRole ?: if (onClick != null) Role.Button else null)?.let { role = it }
            selected = isSelected
            if (!enabled) disabled()
        }
        .then(TvFocusableFocusElement(state, animate = !lowRam, enabled = enabled))
        .then(TvFocusableScaleElement(state, scaleAmount = scaleAmount, allowScaleAndZ = !lowRam))
        .clip(if (isCircle) CircleShape else RoundedCornerShape(12.dp))
        .then(
            TvFocusableBorderElement(
                state = state,
                isSelected = isSelected,
                enabled = enabled,
                isCircle = isCircle,
            ),
        )
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
            } else {
                Modifier.focusable(enabled = enabled, interactionSource = interactionSource)
            },
        )
}
// ---------------------------------------------------------------------------
// Focus tint + border (draw phase — no recomposition)
// ---------------------------------------------------------------------------

private class TvFocusableBorderElement(
    private val state: TvFocusableVisualState,
    private val isSelected: Boolean,
    private val enabled: Boolean,
    private val isCircle: Boolean,
) : ModifierNodeElement<TvFocusableBorderNode>() {
    override fun create(): TvFocusableBorderNode =
        TvFocusableBorderNode(state, isSelected, enabled, isCircle)

    override fun update(node: TvFocusableBorderNode) {
        node.isSelected = isSelected
        node.enabled = enabled
        node.isCircle = isCircle
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusableBorder"
    }

    override fun equals(other: Any?): Boolean = other is TvFocusableBorderElement &&
        other.state === state &&
        other.isSelected == isSelected &&
        other.enabled == enabled &&
        other.isCircle == isCircle

    override fun hashCode(): Int = System.identityHashCode(state) * 31 +
        isSelected.hashCode() * 31 +
        enabled.hashCode() * 31 +
        isCircle.hashCode()
}

private class TvFocusableBorderNode(
    private val state: TvFocusableVisualState,
    isSelected: Boolean,
    enabled: Boolean,
    isCircle: Boolean,
) : Modifier.Node(), DrawModifierNode {
    // Snapshot state so draw re-runs when these visual inputs change.
    var isSelected: Boolean by mutableStateOf(isSelected)
    var enabled: Boolean by mutableStateOf(enabled)
    var isCircle: Boolean by mutableStateOf(isCircle)
    override fun ContentDrawScope.draw() {
        drawContent()
        if (!enabled) return
        val cornerRadius = if (isCircle) {
            CornerRadius(size.minDimension / 2f)
        } else {
            CornerRadius(12.dp.toPx())
        }
        if (state.isFocused) {
            val progress = state.focusProgress
            if (progress > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.15f * progress),
                    cornerRadius = cornerRadius,
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = progress),
                    style = Stroke(width = 3.dp.toPx()),
                    cornerRadius = cornerRadius,
                )
            }
        } else if (isSelected) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.4f),
                style = Stroke(width = 2.dp.toPx()),
                cornerRadius = cornerRadius,
            )
        }
    }
}
// ---------------------------------------------------------------------------
// Scale + z-order (graphics-layer phase — no recomposition, no re-layout)
// ---------------------------------------------------------------------------

private class TvFocusableScaleElement(
    private val state: TvFocusableVisualState,
    private val scaleAmount: Float,
    private val allowScaleAndZ: Boolean,
) : ModifierNodeElement<TvFocusableScaleNode>() {
    override fun create(): TvFocusableScaleNode =
        TvFocusableScaleNode(state, scaleAmount, allowScaleAndZ)

    override fun update(node: TvFocusableScaleNode) {
        node.scaleAmount = scaleAmount
        node.allowScaleAndZ = allowScaleAndZ
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusableScale"
    }

    override fun equals(other: Any?): Boolean = other is TvFocusableScaleElement &&
        other.state === state &&
        other.scaleAmount == scaleAmount &&
        other.allowScaleAndZ == allowScaleAndZ

    override fun hashCode(): Int = System.identityHashCode(state) * 31 +
        scaleAmount.hashCode() * 31 +
        allowScaleAndZ.hashCode()
}

@Suppress("AutoboxingStateCreation")
private class TvFocusableScaleNode(
    private val state: TvFocusableVisualState,
    scaleAmount: Float,
    allowScaleAndZ: Boolean,
) : Modifier.Node(), LayoutModifierNode {
    // Snapshot state so layer/placement re-runs when these change.
    var scaleAmount: Float by mutableStateOf(scaleAmount)
    var allowScaleAndZ: Boolean by mutableStateOf(allowScaleAndZ)
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // Re-placed only when focus flips; the scale animation inside the
            // layer block only re-evaluates the graphics layer, never layout
            // or composition.
            val z = if (allowScaleAndZ && state.isFocused) 10f else 0f
            placeable.placeWithLayer(0, 0, z) {
                if (allowScaleAndZ) {
                    val scale = 1f + (scaleAmount - 1f) * state.focusProgress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin.Center
                } else {
                    // Layer properties survive updates; clear the previous animated scale.
                    scaleX = 1f
                    scaleY = 1f
                }
            }
        }
    }
}
// ---------------------------------------------------------------------------
// Focus observation + focus-progress animation
// ---------------------------------------------------------------------------

private class TvFocusableFocusElement(
    private val state: TvFocusableVisualState,
    private val animate: Boolean,
    private val enabled: Boolean,
) : ModifierNodeElement<TvFocusableFocusNode>() {
    override fun create(): TvFocusableFocusNode = TvFocusableFocusNode(state, animate, enabled)

    override fun update(node: TvFocusableFocusNode) {
        node.refresh(animate, enabled)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "tvFocusable"
    }

    override fun equals(other: Any?): Boolean = other is TvFocusableFocusElement &&
        other.state === state &&
        other.animate == animate &&
        other.enabled == enabled

    override fun hashCode(): Int = System.identityHashCode(state) * 31 +
        animate.hashCode() * 31 +
        enabled.hashCode()
}

private class TvFocusableFocusNode(
    private val state: TvFocusableVisualState,
    private var animate: Boolean,
    private var enabled: Boolean,
) : Modifier.Node(), FocusEventModifierNode {
    private var animationJob: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        val focused = focusState.isFocused
        if (state.isFocused == focused) return
        state.isFocused = focused
        restartAnimation()
    }

    fun refresh(newAnimate: Boolean, newEnabled: Boolean) {
        val changed = newAnimate != animate || newEnabled != enabled
        animate = newAnimate
        enabled = newEnabled
        if (changed) restartAnimation()
    }

    private fun restartAnimation() {
        animationJob?.cancel()
        animationJob = null
        val target = if (state.isFocused && enabled) 1f else 0f
        if (state.focusProgress == target) return
        if (!animate) {
            // Low-RAM devices skip animation entirely for an instant outline.
            state.focusProgress = target
            return
        }
        animationJob = coroutineScope.launch {
            val start = state.focusProgress
            val startTime = withFrameNanos { it }
            val durationNanos = FOCUS_ANIMATION_MS * 1_000_000L
            var frameTime = startTime
            while (true) {
                val linear = ((frameTime - startTime).toFloat() / durationNanos).coerceIn(0f, 1f)
                state.focusProgress = start + (target - start) * FastOutSlowInEasing.transform(linear)
                if (linear >= 1f) break
                frameTime = withFrameNanos { it }
            }
        }
    }

    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
        state.isFocused = false
        state.focusProgress = 0f
    }
}
