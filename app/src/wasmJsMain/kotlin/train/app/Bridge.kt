@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package train.app

import kotlinx.browser.localStorage
import train.core.Storage

// Функции window.bridge из index.html: то, чего нет в Compose

/** `held` — замок `active-workout` у этой вкладки, `busy` — у другой. */
fun lockState(): String = js("window.bridge.lock")

fun wake(on: Boolean): Unit = js("window.bridge.wake(on)")

fun buzz(): Unit = js("navigator.vibrate && navigator.vibrate([200, 100, 200])")

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
