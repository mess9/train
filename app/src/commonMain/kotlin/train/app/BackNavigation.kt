package train.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import train.core.BackLayers

private val LocalBackLayers = staticCompositionLocalOf<BackLayers?> { null }
private val LocalBackPriority = staticCompositionLocalOf { 0 }

@Composable
fun BackProvider(layers: BackLayers, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackLayers provides layers, content = content)
}

/** Приоритет соответствует порядку рисования глобальных слоёв поверх экрана. */
@Composable
fun BackPriority(priority: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBackPriority provides priority, content = content)
}

@Composable
fun NestedBackPriority(offset: Int, content: @Composable () -> Unit) {
    BackPriority(LocalBackPriority.current + offset, content)
}

@Composable
fun BackLayer(enabled: Boolean = true, dismissEnabled: Boolean = true, priority: Int = 0, onBack: () -> Unit) {
    val layers = LocalBackLayers.current ?: return
    val level = LocalBackPriority.current + priority
    val key = remember { Any() }
    val close = rememberUpdatedState(onBack)
    val canClose = rememberUpdatedState(dismissEnabled)
    DisposableEffect(layers, enabled, level) {
        if (enabled) layers.add(key, level, { canClose.value }) { close.value() }
        onDispose { layers.remove(key) }
    }
}
