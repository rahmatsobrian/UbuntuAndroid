package dev.ubuntu4a.core.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Material 3 Expressive motion physics.
 * Expressive uses spring-based motion and "bouncy" overshoots for emphasis.
 */
object UbuntuMotion {
    fun <T : Any> springy(dampingRatio: Float = Spring.DampingRatioMediumBouncy) =
        spring<T>(dampingRatio = dampingRatio, stiffness = Spring.StiffnessMediumLow)

    fun <T : Any> snappy() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)

    const val MORPH_DURATION = 300
    const val STAGGER_MS = 60
}
