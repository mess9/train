package train.app

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AtomicFile
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Cache
import train.core.Storage
import java.io.File
import java.io.FileNotFoundException
import java.net.URLEncoder
import java.util.Calendar

// Android: система вместо window.bridge. Контекст и Activity — AndroidHost

/** Одна Activity — одна копия приложения: замок всегда свой. */
actual fun lockState(): String = "held"

actual fun wake(on: Boolean) {
    AndroidHost.wakeOn = on
    val w = AndroidHost.activity?.window ?: return
    if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

private val vibrator: Vibrator by lazy {
    val c = AndroidHost.context
    if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)
}

/**
 * Назначение вибрации — явно, «медиа». Без него Android 13+ считает короткий толчок откликом на
 * касание и глушит его, если в системе выключена вибрация при касании: оценки молчали бы. Свой
 * выключатель отклика у приложения есть ([hapticsOn]).
 */
private fun play(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    else vibrator.vibrate(effect)
}

/** Толчки по длительностям, мс; на полной силе, если мотор её меняет: 10–20 мс на средней почти не слышны. */
private fun pulses(a: Int, pause: Int, b: Int): VibrationEffect {
    val strong = vibrator.hasAmplitudeControl()
    return if (b > 0) {
        val timings = longArrayOf(0, a.toLong(), pause.toLong(), b.toLong())
        if (strong) VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255), -1) else VibrationEffect.createWaveform(timings, -1)
    } else {
        VibrationEffect.createOneShot(a.toLong(), if (strong) 255 else VibrationEffect.DEFAULT_AMPLITUDE)
    }
}

/** Как `navigator.vibrate([200, 100, 200])` у PWA. */
actual fun buzz() = play(pulses(200, 100, 200))

/**
 * Щелчки — встроенными эффектами (Android 10+): их производитель подстроил под свой мотор, а
 * толчок в 8–24 мс на многих телефонах не ощутим. Двойные толчки «снято» и «проход закрыт» —
 * своим рисунком, как у PWA.
 */
internal actual fun vibrate(h: Haptic) {
    val predefined = when (h) {
        Haptic.Tick, Haptic.Field -> VibrationEffect.EFFECT_TICK
        Haptic.Easy -> VibrationEffect.EFFECT_CLICK
        Haptic.Normal -> VibrationEffect.EFFECT_HEAVY_CLICK
        Haptic.Hard, Haptic.Like -> VibrationEffect.EFFECT_DOUBLE_CLICK
        Haptic.Undo, Haptic.Pass -> null
    }
    play(
        if (predefined != null && Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(predefined)
        else pulses(h.on, h.pause, h.on2),
    )
}

actual fun beep() = Sound.beep()

actual val mediaKeys: Boolean = true

actual val tapHaptics: Boolean = true

private val audio: AudioManager by lazy { AndroidHost.context.getSystemService(AudioManager::class.java) }

/** Как кнопка гарнитуры: система отдаёт клавишу плееру, который играет или играл последним. */
actual fun mediaKey(key: MediaKey) {
    val code = when (key) {
        MediaKey.Prev -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        MediaKey.PlayPause -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        MediaKey.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
    }
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}

actual fun musicPlaying(): Boolean = audio.isMusicActive

/** Клавиатуру учитывают отступы окна (`WindowInsets.safeDrawing` в [AndroidRoot]), а не эта высота. */
actual fun keyboardHeight(): Int = 0

/** Не зовётся: вместо входа Cloudflare у APK сервисный токен ([serverConfigurable]). */
actual fun openLogin() {}

actual fun reload() {
    AndroidHost.activity?.recreate()
}

actual fun appBuild(): String = AndroidHost.build

actual fun pageVisible(): Boolean = AndroidHost.visibleNow()

actual fun pickPhoto(camera: Boolean) = Photo.pick(camera)

actual fun photoState(): String = Photo.state.name

actual fun photoData(): String = Photo.state.data

actual fun photoType(): String = Photo.state.type

actual fun photoTakenAt(): Double = Photo.state.takenAt.toDouble()

actual fun photoMessage(): String = Photo.state.message

actual fun photoReset() = Photo.reset()

@Volatile
private var pickedDate = ""

/** Системный выбор даты; `current`, `max` — `YYYY-MM-DD`. */
actual fun pickDate(current: String, max: String) {
    val a = AndroidHost.activity ?: return
    fun parse(s: String) = runCatching { s.split('-').map(String::toInt).takeIf { it.size == 3 } }.getOrNull()
    val (y, m, d) = parse(current) ?: Calendar.getInstance().let { listOf(it[Calendar.YEAR], it[Calendar.MONTH] + 1, it[Calendar.DAY_OF_MONTH]) }
    val dialog = DatePickerDialog(a, { _, yy, mm, dd -> pickedDate = "%04d-%02d-%02d".format(yy, mm + 1, dd) }, y, m - 1, d)
    parse(max)?.let { (my, mm, md) ->
        dialog.datePicker.maxDate = Calendar.getInstance().apply { set(my, mm - 1, md, 23, 59, 59) }.timeInMillis
    }
    dialog.show()
}

actual fun takeDate(): String = pickedDate.also { pickedDate = "" }

/**
 * Файл на ключ в `filesDir/store`, запись — [AtomicFile]: целиком и с `fsync` до возврата, так что
 * сессия на диске раньше, чем обновится экран (ADR 0007). Прочитанное держим в памяти.
 */
actual object DeviceStorage : Storage {
    internal lateinit var dir: File
    private val known = HashMap<String, String?>()

    private fun file(key: String) = AtomicFile(File(dir, URLEncoder.encode(key, "UTF-8")))

    @Synchronized
    override fun read(key: String): String? {
        if (key in known) return known[key]
        val v = try {
            file(key).readFully().toString(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            null
        }
        known[key] = v
        return v
    }

    @Synchronized
    override fun write(key: String, value: String) {
        val f = file(key)
        val out = f.startWrite()
        try {
            out.write(value.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Throwable) {
            f.failWrite(out)
            throw e
        }
        known[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        file(key).delete()
        known[key] = null
    }
}

actual fun serverOrigin(): String = DeviceStorage.read(SERVER_KEY).orEmpty()

actual val serverConfigurable: Boolean = true

actual val deviceKind: String = train.core.DEVICE_APK

/** Картинки датасета не меняются: как `media`-кэш service worker у PWA, держим их год, и офлайн тоже. */
private const val MEDIA_CACHE = "private, max-age=31536000, immutable"

actual fun httpClient(): HttpClient = HttpClient(OkHttp) {
    // Редирект — вход Cloudflare Access: OkHttp по нему не идёт, ответ разбирает клиент
    followRedirects = false
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            cache(Cache(File(AndroidHost.context.cacheDir, "http"), 200L * 1024 * 1024))
        }
        // Сервисный токен Cloudflare Access из настроек — на каждый запрос, поменять можно на ходу
        addInterceptor { chain ->
            val id = DeviceStorage.read(CF_ID_KEY)
            val secret = DeviceStorage.read(CF_SECRET_KEY)
            val req = chain.request()
            chain.proceed(
                if (id.isNullOrBlank() || secret.isNullOrBlank()) req
                else req.newBuilder().header("CF-Access-Client-Id", id).header("CF-Access-Client-Secret", secret).build(),
            )
        }
        // Cloudflare переписывает Cache-Control на 4 часа (см. sw.js) — возвращаем серверный
        addNetworkInterceptor { chain ->
            val r = chain.proceed(chain.request())
            if (r.isSuccessful && chain.request().url.encodedPath.startsWith("/media/")) r.newBuilder().header("Cache-Control", MEDIA_CACHE).build() else r
        }
    }
}

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

actual fun onOnline(action: () -> Unit) {
    AndroidHost.online += action
}

actual fun onVisible(action: () -> Unit) {
    AndroidHost.visible += action
}

/**
 * Своя история шагов вместо истории браузера: кнопка и жест «назад» системы идут в [systemBack],
 * пока приложение не на главной; на главной «назад» — системе (свернуть). Как у браузера: [go] и
 * [back] меняют глубину и сообщают об этом через [onPop].
 */
actual object BackStack {
    private var depth = 0
    private val listeners = mutableListOf<() -> Unit>()
    private var callback: OnBackPressedCallback? = null

    actual fun depth(): Int = depth

    private fun set(d: Int) {
        depth = d.coerceAtLeast(0)
        callback?.isEnabled = depth > 0
    }

    actual fun push(depth: Int) = set(depth)

    actual fun replace(depth: Int) = set(depth)

    actual fun go(delta: Int) {
        set(depth + delta)
        listeners.toList().forEach { it() }
    }

    actual fun back() = go(-1)

    actual fun onPop(action: () -> Unit) {
        listeners += action
    }

    /** Кнопка «назад» системы: шаг назад по стеку приложения. */
    private fun systemBack() {
        if (depth > 0) back()
    }

    internal fun reset() {
        depth = 0
        listeners.clear()
    }

    /** Обработчик «назад» для новой Activity; включён, пока есть куда возвращаться. */
    internal fun newCallback(): OnBackPressedCallback = object : OnBackPressedCallback(depth > 0) {
        override fun handleOnBackPressed() = systemBack()
    }.also { callback = it }
}
