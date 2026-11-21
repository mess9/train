package train.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import train.core.Exercise

private val greyFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Картинка упражнения из датасета; нет медиа — значок гантели в цвете блока. `grey` — упражнение
 * выполнено: картинка серая и бледнее.
 */
@Composable
fun Thumb(app: AppState, ex: Exercise?, tint: Tint?, size: Dp = 52.dp, modifier: Modifier = Modifier, grey: Boolean = false) {
    val key = ex?.mediaKey
    if (key != null) LaunchedEffect(key) { app.image(key) }
    val img = key?.let { app.images["$it.jpg"] }
    val shape = RoundedCornerShape(if (size < 48.dp) 8.dp else 10.dp)
    if (img != null) {
        Image(
            img, ex.nameRu, modifier.size(size).clip(shape).background(C.Thumb).alpha(if (grey) 0.55f else 1f),
            contentScale = ContentScale.Crop, colorFilter = if (grey) greyFilter else null,
        )
    } else {
        Box(modifier.size(size).clip(shape).background(if (grey) C.DoneLine else tint?.line ?: C.Border), contentAlignment = Alignment.Center) {
            Ico(Icon.Dumbbell, if (size < 48.dp) 22.dp else 28.dp, if (grey) C.Done else tint?.tint ?: C.Muted, 1.6f)
        }
    }
}

/** Гифка — HTML `<img>` поверх canvas: Compose анимацию gif не проигрывает. Место под неё держит Box. */
@Composable
fun Gif(app: AppState, mediaKey: String, modifier: Modifier) {
    val density = LocalDensity.current.density
    val id = "gif-$mediaKey"
    DisposableEffect(id) { onDispose { hideGif(id) } }
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(C.Thumb).onGloballyPositioned { c ->
            val p = c.positionInWindow()
            showGif(
                id, "/media/$mediaKey.gif", app.key.orEmpty(),
                (p.x / density).toDouble(), (p.y / density).toDouble(),
                (c.size.width / density).toDouble(), (c.size.height / density).toDouble(),
            )
        },
    )
}
