package train.app

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Фото прогресса: камера или галерея, сжатие как у PWA (index.html) — длинная сторона до 2000 px,
 * JPEG 85. Общий код опрашивает [state]: `picking` → `busy` → `ready` | `error` | `none`.
 */
internal object Photo {
    class State(
        val name: String,
        val data: String = "",
        val type: String = "image/jpeg",
        val takenAt: Long = System.currentTimeMillis(),
        val message: String = "",
    )

    private const val MAX = 2000
    private const val LIMIT = 15 * 1024 * 1024

    @Volatile
    var state = State("none")
        private set

    fun pick(camera: Boolean) {
        state = State("picking")
        val opened = runCatching { if (camera) AndroidHost.openCamera() else AndroidHost.openGallery() }
        if (opened.getOrDefault(false) != true) state = State("error", message = opened.exceptionOrNull()?.message ?: "выбор фото недоступен")
    }

    fun cancel() {
        if (state.name == "picking") state = State("none")
    }

    fun reset() {
        state = State("none")
    }

    fun use(uri: Uri, fromCamera: Boolean) {
        state = State("busy")
        thread(name = "photo") {
            state = try {
                // Из галереи — когда снято (EXIF, затем база снимков); с камеры — сейчас
                val takenAt = if (fromCamera) System.currentTimeMillis() else takenAt(uri) ?: System.currentTimeMillis()
                compress(uri, takenAt)
            } catch (e: Throwable) {
                State("error", message = e.message ?: e.toString())
            }
        }
    }

    private fun compress(uri: Uri, takenAt: Long): State {
        val resolver = AndroidHost.context.contentResolver
        val bmp = try {
            // ImageDecoder сам поворачивает по EXIF. Уменьшение — степенью двойки при чтении
            // (не зависит от поворота), остальное — масштабом
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (long / (sample * 2) >= MAX) sample *= 2
                d.setTargetSampleSize(sample)
            }
        } catch (e: Exception) {
            // Формат, который система не читает, — как есть: сервер его примет
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw e
            if (bytes.size > LIMIT) error("файл больше 15 МБ")
            return State("ready", Base64.encodeToString(bytes, Base64.NO_WRAP), resolver.getType(uri) ?: "image/heic", takenAt)
        }
        val k = minOf(1f, MAX.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (k >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        val out = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) error("не удалось сжать фото")
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return State("ready", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg", takenAt)
    }

    private fun takenAt(uri: Uri): Long? {
        val resolver = AndroidHost.context.contentResolver
        val exif = runCatching {
            resolver.openInputStream(uri)?.use { s ->
                val e = ExifInterface(s)
                val t = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME)
                val offset = e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                t?.let {
                    if (offset != null) SimpleDateFormat("yyyy:MM:dd HH:mm:ssXXX", Locale.ROOT).parse(it + offset)?.time
                    else SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(it)?.time
                }
            }
        }.getOrNull()
        if (exif != null) return exif
        return runCatching {
            resolver.query(uri, arrayOf("datetaken"), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
            }
        }.getOrNull()
    }
}
