package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.coroutines.delay
import train.client.Link
import train.core.TRAIN_VERSION
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { Root() }
}

@Composable
fun Root() {
    // Экранная клавиатура: весь экран — над ней, листы и прокрутка ведут себя как на меньшем экране
    var keyboard by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            keyboard = keyboardHeight()
            delay(120)
        }
    }
    Box(Modifier.fillMaxSize().background(C.Bg).padding(bottom = keyboard.dp)) {
        // Вторая вкладка ничего не пишет: сессию держит та, у которой замок
        if (lockState() == "busy") {
            LockBusy()
            return@Box
        }
        val scope = rememberCoroutineScope()
        val app = remember { AppState(scope).also { it.start() } }
        if (app.key == null || app.session == null) {
            Connect(app)
            return@Box
        }
        TimerSignals(app)
        val screen = app.screen
        // Состояние экранов в стеке (прокрутка, раскрытые карточки) живёт, пока экран в стеке:
        // вернулся с «Техники» — тренировка на том же месте
        val saved = rememberSaveableStateHolder()
        val keys = app.stack.mapIndexed { i, sc -> "$i:$sc" }
        val seen = remember { mutableSetOf<String>() }
        LaunchedEffect(keys) {
            (seen - keys.toSet()).forEach(saved::removeState)
            seen.retainAll(keys.toSet())
            seen += keys
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                saved.SaveableStateProvider(keys.last()) { when (screen) {
                    Screen.Home -> Home(app)
                    is Screen.PlanView -> PlanView(app, screen.id)
                    Screen.Workout -> WorkoutScreen(app)
                    is Screen.Technique -> Technique(app, screen.exerciseId)
                    Screen.Finish -> Finish(app)
                    Screen.History -> History(app)
                    is Screen.HistoryDetail -> HistoryDetail(app, screen.id)
                    Screen.Measurements -> Measurements(app)
                    Screen.Settings -> Settings(app)
                } }
            }
            if (screen == Screen.Home || screen == Screen.History || screen is Screen.HistoryDetail || screen == Screen.Measurements) {
                NavBar(app, screen)
            }
        }
    }
}

/**
 * Конец отдыха и ручного таймера — вибрация и звук. Здесь, а не на экране тренировки: сигнал
 * нужен и когда открыта «Техника».
 */
@Composable
private fun TimerSignals(app: AppState) {
    val restUntil = app.rest?.until
    LaunchedEffect(restUntil) { signalAt(app, restUntil) }
    val timerUntil = app.tools.timerUntil
    LaunchedEffect(timerUntil) { signalAt(app, timerUntil) }
}

private suspend fun signalAt(app: AppState, at: Instant?) {
    val left = (at ?: return) - Clock.System.now()
    if (!left.isPositive()) return
    delay(left)
    // Настройку читаем в момент сигнала: выключил во время отдыха — не вибрирует
    if (app.vibration) buzz()
    beep()
}

@Composable
private fun NavBar(app: AppState, screen: Screen) {
    Row(Modifier.fillMaxWidth().background(C.Bar).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
        NavBtn(Icon.Home, "Главная", screen == Screen.Home) { app.root(Screen.Home) }
        NavBtn(Icon.History, "История", screen == Screen.History || screen is Screen.HistoryDetail) { app.root(Screen.History) }
        NavBtn(Icon.Ruler, "Замеры", screen == Screen.Measurements) { app.root(Screen.Measurements) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavBtn(icon: Icon, label: String, on: Boolean, onClick: () -> Unit) {
    val color = if (on) C.Lime else C.Muted
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).tap(onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Ico(icon, color = color)
        Txt(label, ts(12.sp, color))
    }
}

/** Связь и очередь — в шапке главной; тап ведёт в настройки. */
@Composable
fun StatusTags(app: AppState) {
    val n = app.session?.outbox?.size ?: 0
    val problem = linkText(app.link)
    if (n == 0 && problem == null && !app.paused && app.rejected == null) return
    Row(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).tap { app.go(Screen.Settings) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (problem != null) LateTag(problem)
        if (app.rejected != null) LateTag("запись отвергнута")
        if (app.paused) Tag("пауза отправки")
        if (n > 0) Tag("не отправлено: $n")
    }
}

@Composable
private fun LockBusy() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Txt("Открыто в другой вкладке", ts(22.sp, weight = FontWeight.Bold), align = TextAlign.Center)
        Gap(8.dp)
        Txt("Тренировку ведёт одна вкладка. Закройте лишнюю и обновите эту.", T.meta, align = TextAlign.Center)
        Gap(20.dp)
        Btn("Обновить", ::reload)
    }
}

@Composable
private fun Connect(app: AppState) {
    var key by remember { mutableStateOf(app.key.orEmpty()) }
    Page {
        Header("Тренировки", "Подключение")
        Txt("Ключ выдаёт `gym admin key` — вставьте его или откройте ссылку из QR-кода.", T.meta.copy(color = C.Soft))
        Gap(16.dp)
        TextArea(key, { key = it }, "Ключ", singleLine = true, minHeight = 46.dp)
        Gap(12.dp)
        val error = app.connectError ?: if (app.key != null) linkText(app.link) else null
        if (error != null) {
            LateTag(error)
            Gap(12.dp)
        }
        if (app.link == Link.ACCESS_LOGIN) {
            Btn("Войти в Cloudflare", ::openLogin, Modifier.fillMaxWidth(), Kind.Ghost)
            Gap(8.dp)
        }
        Btn(if (app.refreshing) "Подключаю…" else "Подключить", { app.connect(key) }, Modifier.fillMaxWidth(), enabled = !app.refreshing, big = true)
    }
}

@Composable
private fun Settings(app: AppState) {
    var key by remember { mutableStateOf("") }
    val s = app.session
    Page {
        BackLink("Назад", app::back)
        Txt("Настройки", T.h1)
        Section("Связь")
        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(app.cache.me?.let { me -> me.name?.let { "$it · ${me.email}" } ?: me.email } ?: "Пользователь неизвестен", T.strong)
                Txt("Связь: " + (linkText(app.link) ?: "есть"), T.meta)
                Txt("Не отправлено: ${s?.outbox?.size ?: 0}", T.meta)
                app.cache.savedAt?.let { Txt("Данные с сервера: ${it.date().dayMonth()}, ${it.clock()}", T.meta) }
            }
        }
        if (app.link == Link.ACCESS_LOGIN) {
            Gap(10.dp)
            Btn("Войти в Cloudflare", ::openLogin, Modifier.fillMaxWidth(), Kind.Ghost)
        }
        Gap(10.dp)
        Btn(if (app.refreshing) "Обновляю…" else "Обновить данные", { app.refresh() }, Modifier.fillMaxWidth(), Kind.Ghost, enabled = !app.refreshing)

        app.rejected?.let { r ->
            Section("Отвергнутая запись")
            Txt("Сервер ответил ${r.status}: ${r.message}. Пока запись в очереди, остальные не уходят.", T.meta.copy(color = C.Soft))
            Gap(10.dp)
            Btn("Убрать её из очереди", app::dropRejected, Modifier.fillMaxWidth(), Kind.Ghost)
        }

        Section("Сигнал отдыха")
        Txt("В конце отдыха и таймера всегда звучит короткий сигнал.", T.meta)
        Gap(10.dp)
        CheckRow("Вибрация", "Два коротких толчка. На iPhone браузер не вибрирует — только звук", app.vibration) {
            val on = !app.vibration
            app.setVibration(on)
            // Показать, какой она будет: тап — жест пользователя, браузер разрешает вибрацию
            if (on) buzz()
        }
        app.profileError?.let {
            Gap(8.dp)
            LateTag(it)
        }

        Section("Версия")
        Surface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val mine = appBuild()
                val info = app.serverInfo
                // Версия проекта общая (ADR 0009): приложение с сервера той же сборки — та же версия с коммитом
                val version = info?.takeIf { it.app == mine }?.version ?: TRAIN_VERSION
                Txt("Приложение: $version · ${mine.take(8)}", T.meta)
                Txt("Сервер: " + (info?.let { i -> listOfNotNull(i.version, i.build?.let { "сборка $it" }).joinToString(" · ") } ?: "нет данных"), T.meta)
                val served = info?.app
                if (served != null && served != mine) {
                    Txt("На сервере приложение ${served.take(8)}. Закройте приложение и откройте снова.", T.meta.copy(color = C.Lime))
                }
            }
        }

        Section("Ключ")
        Txt("Новый ключ заменит текущий. Очередь привязана к пользователю: с ключом другого она не отправится.", T.meta)
        Gap(10.dp)
        TextArea(key, { key = it }, "Новый ключ", singleLine = true, minHeight = 46.dp)
        app.connectError?.let {
            Gap(8.dp)
            LateTag(it)
        }
        Gap(10.dp)
        Btn("Сменить ключ", { app.connect(key); key = "" }, Modifier.fillMaxWidth(), Kind.Ghost, enabled = key.isNotBlank())

        Section("Проверка")
        CheckRow("Пауза отправки", "Отметки копятся в очереди и не уходят на сервер", app.paused) { app.setPaused(!app.paused) }
    }
}

@Composable
private fun CheckRow(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).tap(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, T.strong)
            Txt(sub, T.meta)
        }
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                .then(if (on) Modifier.background(C.Lime) else Modifier.border(2.dp, C.Dash, RoundedCornerShape(6.dp))),
        )
    }
}
