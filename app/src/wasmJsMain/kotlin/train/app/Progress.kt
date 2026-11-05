package train.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import train.client.ApiClient
import train.client.ApiException
import train.client.Link
import train.client.linkOf
import train.core.Photo
import train.core.Stats
import train.core.newId
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Снятое или выбранное фото до загрузки: сжато на телефоне (index.html), ждёт «Сохранить». */
class PhotoDraft(
    val id: Uuid,
    val bytes: ByteArray,
    val preview: ImageBitmap?,
    val takenAt: Instant,
    val workoutId: Uuid?,
)

/** Что сейчас с выбором фото. */
enum class Picking { None, Waiting, Compressing }

/**
 * Раздел «Замеры»: сводка за период с сервера и фото прогресса. Всё в памяти — фото и сводка
 * без сети не нужны, а файлы большие.
 */
class Progress(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val onLink: (Link) -> Unit,
    /** Тренировка ещё в очереди телефона: фото с её id сервер не привязал бы — ссылку на неизвестную он обнуляет. */
    private val workoutPending: (Uuid) -> Boolean,
) {
    // Сводка: по диапазону дат

    val stats = mutableStateMapOf<Pair<LocalDate, LocalDate>, Stats>()
    private val statsLoading = mutableSetOf<Pair<LocalDate, LocalDate>>()

    fun loadStats(from: LocalDate, to: LocalDate, force: Boolean = false) {
        val k = from to to
        if ((k in stats && !force) || k in statsLoading) return
        statsLoading += k
        scope.launch {
            runCatching { api.stats(from, to) }
                .onSuccess { stats[k] = it }
                .onFailure { onLink(linkOf(it)) }
            statsLoading -= k
        }
    }

    // Фото

    var photos by mutableStateOf<List<Photo>>(emptyList())
        private set
    var photosLoaded by mutableStateOf(false)
        private set
    private var photosLoading = false

    fun loadPhotos(force: Boolean = false) {
        if ((photosLoaded && !force) || photosLoading) return
        photosLoading = true
        scope.launch {
            runCatching { api.photos() }
                .onSuccess { photos = it.filter { p -> p.deletedAt == null }.sortedByDescending { p -> p.takenAt }; photosLoaded = true }
                .onFailure { onLink(linkOf(it)) }
            photosLoading = false
        }
    }

    /** Миниатюры: файл фото до 2000 px в памяти не держим — уменьшенная копия. */
    val thumbs = mutableStateMapOf<Uuid, ImageBitmap?>()

    fun thumb(id: Uuid) {
        if (id in thumbs) return
        thumbs[id] = null
        scope.launch {
            runCatching { decode(api.photoFile(id), THUMB) }
                .onSuccess { thumbs[id] = it }
                .onFailure { thumbs.remove(id) }
        }
    }

    /** Открытое на весь экран: одно, крупнее миниатюры. */
    var opened by mutableStateOf<Pair<Uuid, ImageBitmap?>?>(null)
        private set

    fun open(id: Uuid) {
        opened = id to null
        scope.launch {
            runCatching { decode(api.photoFile(id), FULL) }
                .onSuccess { if (opened?.first == id) opened = id to it }
                .onFailure { onLink(linkOf(it)) }
        }
    }

    fun closePhoto() {
        opened = null
    }

    var deleteError by mutableStateOf<String?>(null)
        private set

    /** В корзину сервера: оттуда фото восстановит `gym`, насовсем стирает очистка корзины. */
    fun delete(id: Uuid) {
        deleteError = null
        scope.launch {
            runCatching { api.deletePhoto(id) }
                .onSuccess {
                    photos = photos.filterNot { it.id == id }
                    thumbs.remove(id)
                    if (opened?.first == id) opened = null
                }
                .onFailure { deleteError = "Не удалилось: " + reason(it) }
        }
    }

    // Съёмка и выбор: index.html открывает <input type=file>, сжимает, Kotlin опрашивает

    var picking by mutableStateOf(Picking.None)
        private set
    var pickError by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf<PhotoDraft?>(null)
        private set
    private var poll: Job? = null

    /** Камера или галерея. Вызывать из нажатия — иначе браузер не откроет выбор файла. */
    fun pick(camera: Boolean, workoutId: Uuid?) {
        pickError = null
        uploadError = null
        pickPhoto(camera)
        picking = Picking.Waiting
        poll?.cancel()
        poll = scope.launch {
            while (true) {
                when (photoState()) {
                    "busy" -> picking = Picking.Compressing
                    "ready" -> {
                        val bytes = decodeBase64(photoData())
                        val takenAt = Instant.fromEpochMilliseconds(photoTakenAt().toLong())
                        photoReset()
                        draft = PhotoDraft(newId(), bytes, runCatching { decode(bytes, FULL) }.getOrNull(), takenAt, workoutId)
                        picking = Picking.None
                        return@launch
                    }
                    "error" -> {
                        pickError = "Не получилось открыть фото: " + photoMessage()
                        photoReset()
                        picking = Picking.None
                        return@launch
                    }
                    "none" -> {
                        picking = Picking.None
                        return@launch
                    }
                }
                delay(150)
            }
        }
    }

    /** Выбор закрыли, а браузер не сообщил (нет события `cancel`), — не ждать вечно. */
    fun stopPicking() {
        if (picking != Picking.Waiting) return
        poll?.cancel()
        photoReset()
        picking = Picking.None
    }

    var uploading by mutableStateOf(false)
        private set
    var uploadError by mutableStateOf<String?>(null)
        private set

    /** Не ушло из-за сети: отправится при появлении связи, пока приложение открыто. */
    var waitingForNetwork by mutableStateOf(false)
        private set
    private var lastPose: String? = null
    private var lastTakenAt: Instant? = null

    fun upload(takenAt: Instant, pose: String?) {
        val d = draft ?: return
        if (uploading) return
        lastPose = pose
        lastTakenAt = takenAt
        uploading = true
        uploadError = null
        scope.launch {
            try {
                // Сначала тренировка, потом фото к ней: ждём очередь, но не вечно
                var waited = 0
                while (d.workoutId != null && workoutPending(d.workoutId) && waited++ < 30) delay(1000)
                // Тот же id при повторе: сервер не задвоит фото, если первый ответ потерялся
                val p = api.uploadPhoto(d.id, d.bytes, takenAt, pose, null, d.workoutId)
                photos = (listOf(p) + photos.filterNot { it.id == p.id }).sortedByDescending { it.takenAt }
                runCatching { decode(d.bytes, THUMB) }.onSuccess { thumbs[p.id] = it }
                if (draft === d) draft = null
                waitingForNetwork = false
            } catch (e: Throwable) {
                val link = linkOf(e)
                waitingForNetwork = link == Link.OFFLINE
                uploadError = if (waitingForNetwork) "Нет связи — фото не загружено. Оно ждёт здесь и уйдёт само, когда появится сеть, если не закрывать приложение."
                else "Не загрузилось: " + reason(e)
                if (link != Link.ONLINE) onLink(link)
            } finally {
                uploading = false
            }
        }
    }

    /** Сеть вернулась — фото, которое ждало, уходит само. */
    fun retry() {
        val t = lastTakenAt ?: return
        if (waitingForNetwork && draft != null) upload(t, lastPose)
    }

    fun discard() {
        draft = null
        uploadError = null
        waitingForNetwork = false
    }

    private fun reason(e: Throwable): String = when {
        e is ApiException && e.status == 413 -> "файл больше 15 МБ"
        e is ApiException && e.status == 415 -> "это не фото (нужен JPEG, PNG, WebP или HEIC)"
        e is ApiException && e.status < 500 -> e.message ?: "сервер отказал (${e.status})"
        else -> linkText(linkOf(e)) ?: "ошибка сервера"
    }

    private companion object {
        const val THUMB = 360
        const val FULL = 1400
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(s: String): ByteArray = Base64.decode(s)

/** Картинка, уменьшенная до [max] px по длинной стороне: полный снимок в памяти не держим. */
private fun decode(bytes: ByteArray, max: Int): ImageBitmap {
    val img = Image.makeFromEncoded(bytes)
    val k = minOf(1f, max.toFloat() / maxOf(img.width, img.height))
    if (k >= 1f) return img.toComposeImageBitmap()
    val w = (img.width * k).toInt().coerceAtLeast(1)
    val h = (img.height * k).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(img, Rect.makeWH(w.toFloat(), h.toFloat()))
    return surface.makeImageSnapshot().toComposeImageBitmap().also { img.close() }
}
