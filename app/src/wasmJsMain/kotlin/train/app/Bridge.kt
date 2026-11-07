@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package train.app

import kotlinx.browser.localStorage
import train.core.Storage

// Функции window.bridge из index.html: то, чего нет в Compose

/** `held` — замок `active-workout` у этой вкладки, `busy` — у другой. */
fun lockState(): String = js("window.bridge.lock")

fun wake(on: Boolean): Unit = js("window.bridge.wake(on)")

fun buzz(): Unit = js("navigator.vibrate && navigator.vibrate([200, 100, 200])")

private fun vibrate(a: Int, pause: Int, b: Int): Unit =
    js("navigator.vibrate && navigator.vibrate(b > 0 ? [a, pause, b] : a)")

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

/** Отклик на нажатия включён на этом телефоне (`localStorage`, по умолчанию да). */
var hapticsOn: Boolean = localStorage.getItem("haptics") != "off"
    set(value) {
        field = value
        localStorage.setItem("haptics", if (value) "on" else "off")
    }

fun haptic(h: Haptic) {
    if (hapticsOn) runCatching { vibrate(h.on, h.pause, h.on2) }
}

/** Короткий сигнал через Web Audio. Звук разблокирован первым касанием (index.html). */
fun beep(): Unit = js("window.bridge.beep()")

/** Высота экранной клавиатуры в CSS-пикселях (= dp на canvas), 0 — её нет. */
fun keyboardHeight(): Int = js("window.bridge.keyboard()")

fun showGif(id: String, url: String, key: String, x: Double, y: Double, w: Double, h: Double): Unit =
    js("window.bridge.showGif(id, url, key, x, y, w, h)")

fun hideGif(id: String): Unit = js("window.bridge.hideGif(id)")

/** Вход в Cloudflare Access: страница из сети, мимо service worker. */
fun openLogin(): Unit = js("location.href = '/?login=1'")

fun reload(): Unit = js("location.reload()")

/** Хеш сборки приложения из имени `app.<хеш>.js`, `dev` без `server:appDist`. */
fun appBuild(): String = js("window.bridge.build || 'dev'")

fun pageVisible(): Boolean = js("document.visibilityState === 'visible'")

/** Открыть камеру (`camera`) или галерею; результат — [photoState] и остальные `photo*`. Только из нажатия. */
fun pickPhoto(camera: Boolean): Unit = js("window.bridge.pickPhoto(camera)")

/** `none`, `picking` (открыт выбор), `busy` (сжимается), `ready`, `error`. */
fun photoState(): String = js("window.bridge.photo.state")

/** Сжатый файл в base64. */
fun photoData(): String = js("window.bridge.photo.data || ''")

fun photoType(): String = js("window.bridge.photo.type || 'image/jpeg'")

/** Когда снято, мс эпохи: с камеры — сейчас, из галереи — дата файла. */
fun photoTakenAt(): Double = js("window.bridge.photo.takenAt || Date.now()")

fun photoMessage(): String = js("window.bridge.photo.message || ''")

fun photoReset(): Unit = js("window.bridge.photoReset()")

/** Системный выбор даты: `current`, `max` — `YYYY-MM-DD`. Выбранное — [takeDate]. Только из нажатия. */
fun pickDate(current: String, max: String): Unit = js("window.bridge.pickDate(current, max)")

/** Выбранная дата `YYYY-MM-DD` или пусто — ещё не выбрана. */
fun takeDate(): String = js("window.bridge.takeDate()")

/** `localStorage`: запись синхронная — сессия на диске раньше, чем обновится экран. */
object LocalStorage : Storage {
    override fun read(key: String): String? = localStorage.getItem(key)

    override fun write(key: String, value: String) = localStorage.setItem(key, value)

    override fun remove(key: String) = localStorage.removeItem(key)
}

/** Поставил сердечко — свой толчок, снял — обычный. */
fun likeHaptic(on: Boolean) = haptic(if (on) Haptic.Like else Haptic.Tick)
