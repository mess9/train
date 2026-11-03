@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF121212)
private val Card = Color(0xFF1E1E1E)
private val FieldBg = Color(0xFF2A2A2A)
private val Lime = Color(0xFFC8F25A)
private val Muted = Color(0xFF9A9A9A)

private val text = TextStyle(color = Color.White, fontSize = 16.sp)
private val small = TextStyle(color = Muted, fontSize = 13.sp)

// Мост к window.bridge из index.html
private fun jsStatus(key: String): String = js("String(JSON.parse(window.bridge.status())[key])")
private fun jsWake(): JsAny? = js("window.bridge.wake()")
private fun jsLock(): JsAny? = js("window.bridge.lock()")
private fun jsTestRedirect(): JsAny? = js("window.bridge.testRedirect()")
private fun jsCacheLoaded(): JsAny? = js("window.bridge.cacheLoaded()")
private fun jsSinceStart(): Double = js("window.bridge.sinceStart()")
private fun jsHideGif(id: String): JsAny? = js("window.bridge.hideGif(id)")
private fun jsShowGif(id: String, url: String, key: String, x: Double, y: Double, w: Double, h: Double): JsAny? =
    js("window.bridge.showGif(id, url, key, x, y, w, h)")

private val ratings = listOf("Легко", "Норм", "Тяжело")
private const val MARKS_KEY = "spike.marks"

private fun loadMarks(): Map<Int, String> =
    (localStorage.getItem(MARKS_KEY) ?: "").split(",").filter { ":" in it }
        .associate { it.substringBefore(":").toInt() to it.substringAfter(":") }

private fun saveMarks(marks: Map<Int, String>) {
    localStorage.setItem(MARKS_KEY, marks.entries.joinToString(",") { "${it.key}:${it.value}" })
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}

@Composable
fun App() {
    val status = remember { mutableStateMapOf<String, String>() }
    val marks = remember { mutableStateMapOf<Int, String>().apply { putAll(loadMarks()) } }
    var startMs by remember { mutableStateOf<Double?>(null) }
    var ktor by remember { mutableStateOf("—") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        startMs = jsSinceStart()
        jsCacheLoaded()
        jsLock()
        while (true) {
            for (k in listOf("wake", "lock", "redirect", "gif", "sw", "online", "input")) status[k] = jsStatus(k)
            delay(500)
        }
    }

    fun mark(n: Int, r: String) {
        if (marks[n] == r) marks.remove(n) else marks[n] = r
        saveMarks(marks) // синхронно, до следующего кадра
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
                BasicText("Проверка Compose/Wasm", style = text.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
                BasicText("Кириллица: Жим гантелей лёжа · Ёё Щщ Ъъ Ыы", style = text)
                BasicText("Жирный: Разминка · Круг × 3 · Заминка", style = text.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                StatusLine("Первый кадр", startMs?.let { "${it.toInt()} мс" } ?: "…")
                StatusLine("Сеть", status["online"])
                StatusLine("Service worker", status["sw"])
                StatusLine("Wake lock", status["wake"])
                StatusLine("Web Lock", status["lock"])
                StatusLine("Gif", status["gif"])
                StatusLine("Редирект", status["redirect"])
                StatusLine("Редирект", ktor)
                StatusLine("Поле ввода", status["input"])
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Не гасить экран") { jsWake() }
                    Btn("Редирект") {
                        jsTestRedirect()
                        scope.launch { ktor = ktorRedirect() }
                    }
                }
            }
        }
        item { ExerciseCard() }
        items((1..30).toList()) { n ->
            Row(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText("Подход $n", style = text)
                    BasicText(marks[n]?.let { "отмечен: $it" } ?: "22 кг × 10", style = small)
                }
                for (r in ratings) {
                    Btn(r, selected = marks[n] == r, compact = true) { mark(n, r) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private suspend fun ktorRedirect(): String {
    val client = HttpClient(Js) {
        followRedirects = false
        expectSuccess = false
    }
    return try {
        val r = client.get(window.location.origin + "/redirect")
        "ktor: status=${r.status.value}"
    } catch (e: Throwable) {
        "ktor: ${e::class.simpleName}: ${e.message}"
    } finally {
        client.close()
    }
}

@Composable
private fun ExerciseCard() {
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HtmlGif("bench", "/media/test.gif", Modifier.size(96.dp))
            Column {
                BasicText("Жим гантелей лёжа", style = text.copy(fontWeight = FontWeight.Bold))
                BasicText("цель 22 кг × 8–10 · отдых 90 с", style = small)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("кг", "22", KeyboardType.Decimal)
            NumField("повторы", "10", KeyboardType.Number)
            NumField("сек", "", KeyboardType.Number)
        }
        Spacer(Modifier.height(10.dp))
        // Какой KeyboardType даёт цифровую клавиатуру на телефоне
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Phone", "", KeyboardType.Phone)
            NumField("NumPass", "", KeyboardType.NumberPassword)
            NumField("Decimal", "", KeyboardType.Decimal)
        }
    }
}

// Значение поля сохраняется при каждом вводе — как отметка подхода
@Composable
private fun NumField(label: String, initial: String, type: KeyboardType) {
    val key = "spike.field.$label"
    var v by remember { mutableStateOf(localStorage.getItem(key) ?: initial) }
    Column {
        BasicText(label, style = small)
        BasicTextField(
            value = v,
            onValueChange = { v = it; localStorage.setItem(key, it) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            textStyle = text.copy(fontSize = 20.sp),
            cursorBrush = SolidColor(Lime),
            modifier = Modifier.width(88.dp).background(FieldBg, RoundedCornerShape(8.dp)).padding(10.dp),
        )
    }
}

// Gif — HTML <img> поверх canvas: Compose анимацию gif не проигрывает.
@Composable
private fun HtmlGif(id: String, url: String, modifier: Modifier) {
    val density = LocalDensity.current.density
    DisposableEffect(id) { onDispose { jsHideGif(id) } }
    Box(
        modifier.background(FieldBg, RoundedCornerShape(8.dp)).onGloballyPositioned { c ->
            val p = c.positionInWindow()
            jsShowGif(
                id, url, "spike",
                (p.x / density).toDouble(), (p.y / density).toDouble(),
                (c.size.width / density).toDouble(), (c.size.height / density).toDouble(),
            )
        },
    )
}

@Composable
private fun StatusLine(name: String, value: String?) {
    Row {
        BasicText("$name: ", style = small)
        BasicText(value ?: "…", style = text.copy(fontSize = 13.sp))
    }
}

@Composable
private fun Btn(label: String, selected: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Lime else FieldBg, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Lime else Color(0xFF3A3A3A), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 10.dp else 12.dp),
    ) {
        BasicText(label, style = text.copy(fontSize = if (compact) 13.sp else 15.sp, color = if (selected) Bg else Color.White))
    }
}
