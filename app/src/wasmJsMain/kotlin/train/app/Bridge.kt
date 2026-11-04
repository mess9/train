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

/** `localStorage`: запись синхронная — сессия на диске раньше, чем обновится экран. */
object LocalStorage : Storage {
    override fun read(key: String): String? = localStorage.getItem(key)

    override fun write(key: String, value: String) = localStorage.setItem(key, value)

    override fun remove(key: String) = localStorage.removeItem(key)
}
