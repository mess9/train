package train.app

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import train.core.Storage

// То, чего нет в Compose. PWA — window.bridge из index.html и API браузера (wasmJsMain),
// Android — система (androidMain). Общий код зовёт только эти функции

/** `held` — замок `active-workout` у этой копии приложения, `busy` — у другой (вкладки браузера). */
expect fun lockState(): String

/** Не гасить экран: тренировка идёт. */
expect fun wake(on: Boolean)

/** Сигнал конца отдыха: два толчка. */
expect fun buzz()

/** Отклик [h]: у PWA — толчки по его длительностям, у Android — свой эффект системы. Новый перебивает идущий. */
internal expect fun vibrate(h: Haptic)

/** Короткий звуковой сигнал. */
expect fun beep()

/** Клавиша плеера, который играет в фоне (Spotify, YouTube Music…). */
enum class MediaKey { Prev, PlayPause, Next }

/** Управлять чужим плеером можно: у APK — медиа-клавиши системы, у браузера такого нет. */
expect val mediaKeys: Boolean

/** Нажать [key] плеера, как кнопкой гарнитуры. */
expect fun mediaKey(key: MediaKey)

/** Сейчас что-то играет: показать «пауза», а не «играть». */
expect fun musicPlaying(): Boolean

/** Высота экранной клавиатуры в dp, 0 — её нет. */
expect fun keyboardHeight(): Int

/** Вход в Cloudflare Access (PWA — страница входа). У APK не зовётся: вместо входа — сервисный токен. */
expect fun openLogin()

/** Перезапустить приложение: новая сборка или сброс. */
expect fun reload()

/** Хеш сборки приложения, `dev` — локальная. Сравнивается с тем, что отдаёт сервер. */
expect fun appBuild(): String

/** Приложение сейчас на экране. */
expect fun pageVisible(): Boolean

/** Открыть камеру (`camera`) или галерею; результат — [photoState] и остальные `photo*`. Только из нажатия. */
expect fun pickPhoto(camera: Boolean)

/** `none`, `picking` (открыт выбор), `busy` (сжимается), `ready`, `error`. */
expect fun photoState(): String

/** Сжатый файл в base64. */
expect fun photoData(): String

expect fun photoType(): String

/** Когда снято, мс эпохи: с камеры — сейчас, из галереи — дата файла. */
expect fun photoTakenAt(): Double

expect fun photoMessage(): String

expect fun photoReset()

/** Системный выбор даты: `current`, `max` — `YYYY-MM-DD`. Выбранное — [takeDate]. Только из нажатия. */
expect fun pickDate(current: String, max: String)

/** Выбранная дата `YYYY-MM-DD` или пусто — ещё не выбрана. */
expect fun takeDate(): String

/** Хранилище телефона: запись синхронная — сессия на диске раньше, чем обновится экран (ADR 0007). */
expect object DeviceStorage : Storage

/** Адрес сервера без `/` на конце. PWA — откуда загружена страница, APK — из ссылки подключения. */
expect fun serverOrigin(): String

/**
 * Адрес сервера и сервисный токен Cloudflare Access задаёт пользователь: у APK их неоткуда взять.
 * У PWA адрес — страница, а Cloudflare пускает по куке после входа ([openLogin]).
 */
expect val serverConfigurable: Boolean

/** HTTP-клиент без перехода по редиректам: редирект — вход Cloudflare Access (`Link.ACCESS_LOGIN`). */
expect fun httpClient(): HttpClient

/** Картинка из JPEG/PNG, уменьшенная до [max] px по длинной стороне: полный снимок в памяти не держим. */
expect fun decodeImage(bytes: ByteArray, max: Int = Int.MAX_VALUE): ImageBitmap

/** Сеть появилась. */
expect fun onOnline(action: () -> Unit)

/** Приложение вернулось на экран. */
expect fun onVisible(action: () -> Unit)

/**
 * Шаги «назад» системы: у PWA — история браузера (жест «назад» Android закрывает экран, а не
 * сайт), у Android — кнопка «назад». Глубина — номер экрана в стеке [AppState], 0 — главная.
 */
expect object BackStack {
    /** Глубина, на которой стоит система; 0 — если не знает. */
    fun depth(): Int

    /** Добавить шаг глубины [depth]. */
    fun push(depth: Int)

    /** Пометить текущий шаг глубиной [depth], не добавляя нового. */
    fun replace(depth: Int)

    /** Сдвинуться на [delta] шагов (отрицательное — назад); потом придёт [onPop]. */
    fun go(delta: Int)

    /** Шаг назад; потом придёт [onPop]. */
    fun back()

    /** Система перешла на другой шаг: «назад» пользователя или [go]/[back]. */
    fun onPop(action: () -> Unit)
}

/**
 * Отклик на нажатие: толчок, мс — `on`, `pause`, второй `on2` (0 — одиночный). У разных мест чуть
 * разный, чтобы на ощупь отличать «нажал кнопку» от «отметил подход». Новый толчок перебивает
 * идущий. iPhone браузер не вибрирует.
 */
enum class Haptic(val on: Int, val pause: Int = 0, val on2: Int = 0) {
    /** Кнопка, пункт меню, вкладка. */
    Tick(8),

    /** Фокус в поле ввода. */
    Field(5),

    /** Оценки: лёгко — короткий, норма — дольше, тяжело — двойной. */
    Easy(12),
    Normal(24),
    Hard(18, 50, 18),

    /** Сердечко. */
    Like(10, 40, 22),

    /** Снята отметка подхода. */
    Undo(6, 30, 6),

    /** Закрыт проход круга или всё упражнение. */
    Pass(14, 60, 34),
}

/**
 * Отклик на нажатия вообще есть: у APK — да. У PWA нет: короткий `navigator.vibrate` Android
 * глушит, когда в системе выключена вибрация при касании, и браузеру это не обойти, — настройка
 * в PWA неактивна, с подсказкой про приложение.
 */
expect val tapHaptics: Boolean

/** Отклик на нажатия включён на этом телефоне (по умолчанию да). */
var hapticsOn: Boolean = DeviceStorage.read("haptics") != "off"
    set(value) {
        field = value
        DeviceStorage.write("haptics", if (value) "on" else "off")
    }

fun haptic(h: Haptic) {
    if (tapHaptics && hapticsOn) runCatching { vibrate(h) }
}

/** Поставил сердечко — свой толчок, снял — обычный. */
fun likeHaptic(on: Boolean) = haptic(if (on) Haptic.Like else Haptic.Tick)
