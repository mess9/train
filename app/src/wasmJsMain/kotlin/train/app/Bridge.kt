@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package train.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import train.core.Storage

// PWA: функции window.bridge из index.html и API браузера

actual fun lockState(): String = js("window.bridge.lock")

actual fun wake(on: Boolean): Unit = js("window.bridge.wake(on)")

actual fun buzz(): Unit = js("navigator.vibrate && navigator.vibrate([200, 100, 200])")

internal actual fun vibrate(a: Int, pause: Int, b: Int): Unit =
    js("navigator.vibrate && navigator.vibrate(b > 0 ? [a, pause, b] : a)")

/** Web Audio; звук разблокирован первым касанием (index.html). */
actual fun beep(): Unit = js("window.bridge.beep()")

/** CSS-пиксели = dp на canvas. */
actual fun keyboardHeight(): Int = js("window.bridge.keyboard()")

/** HTML `<img>` поверх canvas: Compose анимацию gif не проигрывает. */
actual fun showGif(id: String, url: String, key: String, x: Double, y: Double, w: Double, h: Double): Unit =
    js("window.bridge.showGif(id, url, key, x, y, w, h)")

actual fun hideGif(id: String): Unit = js("window.bridge.hideGif(id)")

/** Страница из сети, мимо service worker. */
actual fun openLogin(): Unit = js("location.href = '/?login=1'")

actual fun reload(): Unit = js("location.reload()")

/** Из имени `app.<хеш>.js`, `dev` без `server:appDist`. */
actual fun appBuild(): String = js("window.bridge.build || 'dev'")

actual fun pageVisible(): Boolean = js("document.visibilityState === 'visible'")

actual fun pickPhoto(camera: Boolean): Unit = js("window.bridge.pickPhoto(camera)")

actual fun photoState(): String = js("window.bridge.photo.state")

actual fun photoData(): String = js("window.bridge.photo.data || ''")

actual fun photoType(): String = js("window.bridge.photo.type || 'image/jpeg'")

actual fun photoTakenAt(): Double = js("window.bridge.photo.takenAt || Date.now()")

actual fun photoMessage(): String = js("window.bridge.photo.message || ''")

actual fun photoReset(): Unit = js("window.bridge.photoReset()")

actual fun pickDate(current: String, max: String): Unit = js("window.bridge.pickDate(current, max)")

actual fun takeDate(): String = js("window.bridge.takeDate()")

/** `localStorage`: запись синхронная. */
actual object DeviceStorage : Storage {
    override fun read(key: String): String? = localStorage.getItem(key)

    override fun write(key: String, value: String) = localStorage.setItem(key, value)

    override fun remove(key: String) = localStorage.removeItem(key)
}

actual fun serverOrigin(): String = window.location.origin

actual fun httpClient(): HttpClient = HttpClient(Js) { followRedirects = false }

actual fun decodeImage(bytes: ByteArray, max: Int): ImageBitmap {
    val img = Image.makeFromEncoded(bytes)
    val k = minOf(1f, max.toFloat() / maxOf(img.width, img.height))
    if (k >= 1f) return img.toComposeImageBitmap()
    val w = (img.width * k).toInt().coerceAtLeast(1)
    val h = (img.height * k).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(img, Rect.makeWH(w.toFloat(), h.toFloat()))
    return surface.makeImageSnapshot().toComposeImageBitmap().also { img.close() }
}

actual fun onOnline(action: () -> Unit) = window.addEventListener("online") { action() }

actual fun onVisible(action: () -> Unit) = document.addEventListener("visibilitychange") { if (pageVisible()) action() }

/** История браузера: в `state` записи — глубина. */
actual object BackStack {
    actual fun depth(): Int = (window.history.state as? JsNumber)?.toInt() ?: 0

    actual fun push(depth: Int) = window.history.pushState(depth.toJsNumber(), "")

    actual fun replace(depth: Int) = window.history.replaceState(depth.toJsNumber(), "")

    actual fun go(delta: Int) = window.history.go(delta)

    actual fun back() = window.history.back()

    actual fun onPop(action: () -> Unit) = window.addEventListener("popstate") { action() }
}
