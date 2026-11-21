package train.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import train.core.Storage

// Android. Этап 1 плана (docs/PLAN-android.md): общий код собирается под Android; то, что требует
// Activity и системных служб, — заглушки до этапов 2–3. Заглушка ведёт себя как «функции нет»

/** Одна Activity — одна копия приложения: замок всегда свой. */
actual fun lockState(): String = "held"

actual fun wake(on: Boolean) {}

actual fun buzz() {}

internal actual fun vibrate(a: Int, pause: Int, b: Int) {}

actual fun beep() {}

/** Клавиатуру на Android учитывают отступы окна (`WindowInsets.ime`), а не эта высота. */
actual fun keyboardHeight(): Int = 0

actual fun showGif(id: String, url: String, key: String, x: Double, y: Double, w: Double, h: Double) {}

actual fun hideGif(id: String) {}

actual fun openLogin() {}

actual fun reload() {}

actual fun appBuild(): String = "dev"

actual fun pageVisible(): Boolean = true

/** Выбора фото пока нет: сразу «не получилось». */
actual fun pickPhoto(camera: Boolean) {}

actual fun photoState(): String = "none"

actual fun photoData(): String = ""

actual fun photoType(): String = "image/jpeg"

actual fun photoTakenAt(): Double = System.currentTimeMillis().toDouble()

actual fun photoMessage(): String = ""

actual fun photoReset() {}

actual fun pickDate(current: String, max: String) {}

actual fun takeDate(): String = ""

/** Пока в памяти; этап 2 — файл в `filesDir` с атомарной записью. */
actual object DeviceStorage : Storage {
    private val map = mutableMapOf<String, String>()

    override fun read(key: String): String? = map[key]

    override fun write(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

/** Этап 2: из настроек (ссылка `…/#key=…`). */
actual fun serverOrigin(): String = ""

actual fun httpClient(): HttpClient = HttpClient(OkHttp) { followRedirects = false }

actual fun decodeImage(bytes: ByteArray, max: Int): ImageBitmap {
    // Сначала только размеры: снимок с телефона целиком в памяти не нужен
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val long = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (long / (sample * 2) >= max) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("картинка не читается")
    val k = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    if (k >= 1f) return bmp.asImageBitmap()
    val w = (bmp.width * k).toInt().coerceAtLeast(1)
    val h = (bmp.height * k).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bmp, w, h, true).also { if (it !== bmp) bmp.recycle() }.asImageBitmap()
}

/** Этап 3: `ConnectivityManager.NetworkCallback`. */
actual fun onOnline(action: () -> Unit) {}

/** Этап 3: жизненный цикл Activity. */
actual fun onVisible(action: () -> Unit) {}

/**
 * Своя история шагов: Activity зовёт [systemBack] на кнопку «назад» (этап 2, `BackHandler`).
 * Как у браузера: [go] и [back] меняют глубину и сообщают об этом через [onPop].
 */
actual object BackStack {
    private var depth = 0
    private val listeners = mutableListOf<() -> Unit>()

    actual fun depth(): Int = depth

    actual fun push(depth: Int) {
        this.depth = depth
    }

    actual fun replace(depth: Int) {
        this.depth = depth
    }

    actual fun go(delta: Int) {
        depth = (depth + delta).coerceAtLeast(0)
        listeners.forEach { it() }
    }

    actual fun back() = go(-1)

    actual fun onPop(action: () -> Unit) {
        listeners += action
    }

    /** Кнопка «назад» системы: `false` — уже на главной, пусть закрывает Activity. */
    fun systemBack(): Boolean {
        if (depth == 0) return false
        back()
        return true
    }
}
