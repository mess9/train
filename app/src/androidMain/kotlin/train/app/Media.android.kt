package train.app

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Последние гифки в памяти: открыл «Технику» снова — без запроса (на диске их держит HTTP-кэш). */
private val gifs = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray) = value.size
}

/** `AnimatedImageDrawable` (Android 9+) в `ImageView`: Compose gif не проигрывает. */
@Composable
actual fun Gif(app: AppState, mediaKey: String, modifier: Modifier) {
    var gif by remember(mediaKey) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(mediaKey) {
        val bytes = gifs[mediaKey] ?: runCatching { app.api.media("$mediaKey.gif") }.getOrNull()?.also { gifs.put(mediaKey, it) } ?: return@LaunchedEffect
        gif = withContext(Dispatchers.Default) { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(C.Thumb)) {
        val d = gif ?: return@Box
        DisposableEffect(d) { onDispose { (d as? AnimatedImageDrawable)?.stop() } }
        AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                v.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.start()
            },
        )
    }
}
