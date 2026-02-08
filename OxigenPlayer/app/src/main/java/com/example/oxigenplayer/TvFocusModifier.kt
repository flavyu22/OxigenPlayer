package com.example.oxigenplayer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Modifier principal pentru suport TV.
 * Creează un efect vizual modern și elegant prin scalare subtilă,
 * bordură albă și un overlay discret.
 */
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    isCircle: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    isSelected: Boolean = false // Păstrăm parametrul dar îl folosim doar pentru stilizare internă
): Modifier = composed {
    val actualInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by actualInteractionSource.collectIsFocusedAsState()
    
    // Scalare subtilă pentru feedback vizual (10% mărire la focus)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "FocusScale"
    )

    val shape = if (isCircle) CircleShape else RoundedCornerShape(12.dp)

    this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .scale(scale)
        .focusable(interactionSource = actualInteractionSource)
        .then(
            if (isFocused) {
                Modifier
                    .background(Color.White.copy(alpha = 0.2f), shape)
                    .border(2.dp, Color.White, shape)
            } else if (isSelected) {
                // Dacă este selectat dar NU are focus, punem o bordură mai discretă sau un fundal
                Modifier
                    .background(Color.White.copy(alpha = 0.1f), shape)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
            } else {
                Modifier
            }
        )
}
