package train.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image
import train.client.ApiClient
import train.client.Link
import train.client.SendResult
import train.client.linkOf
import train.client.send
import train.core.BlockRole
import train.core.Comparison
import train.core.Exercise
import train.core.ServerInfo
import train.core.LayoutBlock
import train.core.Me
import train.core.Measurement
import train.core.Plan
import train.core.Session
import train.core.SetFact
import train.core.SyncRecord
import train.core.TrainJson
import train.core.Workout
import train.core.WorkoutLayout
import train.core.accepted
import train.core.conflict
import train.core.loadSession
import train.core.newId
import train.core.saveSession
import train.core.snapshot
import train.core.start
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Экраны. Подключение и «открыто в другой вкладке» — не в стеке, их выбирает [App]. */
sealed interface Screen {
    data object Home : Screen

    data class PlanView(val id: Uuid) : Screen

    data object Workout : Screen

    data class Technique(val exerciseId: Uuid) : Screen

    data object Finish : Screen

    data object History : Screen

    data class HistoryDetail(val id: Uuid) : Screen

    data object Measurements : Screen

    data object Settings : Screen
}

/** Что телефон помнит с прошлой связи (ключ `cache`). Потерять не страшно — подтянется заново. */
@Serializable
data class Cache(
    val me: Me? = null,
    val plans: List<Plan> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    /** Последние подходы по каждому упражнению справочника. */
    val lastSets: List<SetFact> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val savedAt: Instant? = null,
)

/** Отдых по плану после отметки. */
data class RestState(val until: Instant, val total: Int, val label: String)

/** Таймер и секундомер из шапки тренировки: живут, пока закрыт лист. */
data class Tools(
    val tab: Int = 0,
    val timerDur: Int = 90,
    val timerLeft: Int = 90,
    val timerUntil: Instant? = null,
    val swAcc: Long = 0,
    val swStart: Instant? = null,
)

const val KEY_KEY = "key"
const val CACHE_KEY = "cache"
const val PAUSE_KEY = "pause"

class AppState(private val scope: CoroutineScope) {
    private val storage = LocalStorage
    private val http = HttpClient(Js) { followRedirects = false }

    var key by mutableStateOf(storage.read(KEY_KEY))
        private set
    val api = ApiClient(http, window.location.origin, key)

    /** Клиент с этим ключом: ответ точно относится к нему, даже если ключ тем временем сменили. */
    private fun client(k: String) = ApiClient(http, window.location.origin, k)

    /** Ключ и его пользователь, подтверждённый `/me` в этом запуске: до этого очередь не уходит. */
    private var owner: Pair<String, Uuid>? = null

    var session by mutableStateOf(storage.loadSession())
        private set
    var cache by mutableStateOf(storage.read(CACHE_KEY)?.let { runCatching { TrainJson.decodeFromString(Cache.serializer(), it) }.getOrNull() } ?: Cache())
        private set
    var link by mutableStateOf(Link.ONLINE)
        private set

    /** Сервер отверг первую запись очереди: дальше не шлём, пока её не убрать. */
    var rejected by mutableStateOf<SendResult.Rejected?>(null)
        private set
    var paused by mutableStateOf(storage.read(PAUSE_KEY) == "1")
        private set
    var refreshing by mutableStateOf(false)
        private set
    var connectError by mutableStateOf<String?>(null)
        private set

    /** Версия сервера и сборка приложения на нём — с последнего обновления данных, не в кэше. */
    var serverInfo by mutableStateOf<ServerInfo?>(null)
        private set

    var rest by mutableStateOf<RestState?>(null)
    var tools by mutableStateOf(Tools())

    // Навигация: стек экранов в такт истории браузера — «назад» на Android работает как в приложении.
    // Каждая запись истории помнит свою глубину; popstate сверяет стек с ней. Счётчика «пропустить
    // следующий popstate» нет: потерянное или лишнее событие не съедает следующее «назад».

    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
        private set

    val screen get() = stack.last()

    private val depth get() = stack.size - 1

    private fun historyDepth(): Int = (window.history.state as? JsNumber)?.toInt() ?: 0

    fun go(s: Screen) {
        stack = stack + s
        window.history.pushState(depth.toJsNumber(), "")
    }

    /** Заменить текущий экран, не добавляя шаг «назад». */
    fun replace(s: Screen) {
        stack = stack.dropLast(1) + s
    }

    /** Вкладки нижней панели и выход из тренировки: стек сначала. */
    fun root(s: Screen) {
        val from = depth
        stack = if (s == Screen.Home) listOf(Screen.Home) else listOf(Screen.Home, s)
        // Запись нужной глубины уже есть в истории — встаём на неё, новых не добавляем
        if (from > depth) window.history.go(depth - from)
        else if (from < depth) window.history.pushState(depth.toJsNumber(), "")
    }

    fun back() {
        if (stack.size <= 1) return
        if (historyDepth() == depth) {
            window.history.back()
        } else {
            // История разошлась со стеком — не ждём popstate, которого может не быть
            stack = stack.dropLast(1)
            window.history.replaceState(depth.toJsNumber(), "")
        }
    }

    private fun onPop() {
        val d = historyDepth()
        if (d < depth) stack = stack.take(d + 1)
    }

    // Сессия: каждое действие сначала пишется в localStorage

    fun act(change: Session.() -> Session) {
        val s = session ?: return
        val next = s.change()
        if (next == s) return
        storage.saveSession(next)
        session = next
        poke()
    }

    fun startWorkout(plan: Plan?) {
        val s = session ?: return
        if (s.active != null) {
            go(Screen.Workout)
            return
        }
        val layout = plan?.body?.snapshot(::exercise)
            ?: WorkoutLayout(listOf(LayoutBlock(newId(), BlockRole.MAIN, "Тренировка", emptyList())))
        val ids = layout.blocks.flatMap { b -> b.items.map { it.exerciseId } }.toSet()
        val exercises = cache.exercises.filter { it.id in ids }
        val previous = cache.lastSets.filter { it.exerciseId in ids }
        act { start(plan, layout, exercises, previous, Clock.System.now()) }
        rest = null
        wake(true)
        // На тренировке «назад» ведёт на главную, а не в план; шаг истории есть и без плана
        root(Screen.Workout)
    }

    fun exercise(id: Uuid): Exercise? = session?.exercise(id) ?: cache.exercises.firstOrNull { it.id == id }

    fun plan(id: Uuid?): Plan? = id?.let { pid -> cache.plans.firstOrNull { it.id == pid } }

    fun workoutTitle(w: Workout?): String = plan(w?.planId)?.title ?: "Тренировка"

    // Подключение

    fun connect(newKey: String) {
        val k = newKey.trim()
        if (k.isEmpty()) return
        connectError = null
        scope.launch {
            // Общий клиент не трогаем, пока ключ не проверен
            val me = runCatching { client(k).me() }.getOrElse { e ->
                link = linkOf(e)
                connectError = linkText(link) ?: (e.message ?: "ошибка")
                return@launch
            }
            storage.write(KEY_KEY, k)
            key = k
            api.key = k
            adopt(me, k)
            refresh()
        }
    }

    /** Очередь привязана к пользователю: чужой ключ ничего из неё не отправит (ADR 0008). */
    private fun adopt(me: Me, k: String) {
        val s = session
        when {
            s == null || (s.userId != me.userId && s.workout == null && s.outbox.size == 0) -> {
                val fresh = Session(me.userId)
                storage.saveSession(fresh)
                session = fresh
                link = Link.ONLINE
            }
            s.userId != me.userId -> link = Link.OTHER_USER
            else -> if (link == Link.OTHER_USER) link = Link.ONLINE
        }
        saveCache(cache.copy(me = me))
        owner = k to me.userId
        poke()
    }

    fun forgetKey() {
        storage.remove(KEY_KEY)
        key = null
        api.key = null
        owner = null
    }

    // Справочные данные с сервера

    private var lastRefresh: Instant? = null

    fun refresh(force: Boolean = true) {
        val k = key
        if (k == null || refreshing) return
        val now = Clock.System.now()
        if (!force && lastRefresh?.let { now - it < 30.seconds } == true) return
        lastRefresh = now
        refreshing = true
        scope.launch {
            try {
                val c = client(k)
                val me = c.me()
                // Ключ сменили, пока шёл запрос, — ответ про другого пользователя
                if (key != k) return@launch
                adopt(me, k)
                serverInfo = runCatching { c.info() }.getOrNull() ?: serverInfo
                val today = now.date()
                val plans = c.plans(today.minus(DatePeriod(days = 60)), today.plus(DatePeriod(days = 14)))
                val exercises = c.exercises()
                val workouts = c.workouts(today.minus(DatePeriod(days = 120)), null)
                val measurements = c.measurements()
                val lastSets = exercises.map { it.id }.chunked(40).flatMap { c.lastSets(it) }
                if (key != k) return@launch
                saveCache(withUnsent(Cache(me, plans, exercises, lastSets, workouts, measurements, now)))
                if (link != Link.OTHER_USER) link = Link.ONLINE
                poke()
            } catch (e: Throwable) {
                link = linkOf(e)
                if (link == Link.ONLINE) link = Link.SERVER_ERROR
            } finally {
                refreshing = false
            }
        }
    }

    private fun saveCache(c: Cache) {
        cache = c
        runCatching { storage.write(CACHE_KEY, TrainJson.encodeToString(Cache.serializer(), c)) }
    }

    /**
     * Неотправленное из очереди — поверх ответа сервера: обновление не прячет из истории и
     * замеров то, чего сервер ещё не видел.
     */
    private fun withUnsent(c: Cache): Cache {
        val s = session?.takeIf { it.userId == c.me?.userId } ?: return c
        val local = s.outbox.pending.map { it.record }
        return c.copy(
            workouts = merged(c.workouts, local.filterIsInstance<Workout>()).sortedByDescending { it.startedAt },
            measurements = merged(c.measurements, local.filterIsInstance<Measurement>()).sortedByDescending { it.measuredAt },
        )
    }

    private fun <T : SyncRecord> merged(base: List<T>, newer: List<T>): List<T> {
        val ids = newer.map { it.id }.toSet()
        return base.filterNot { it.id in ids } + newer.filterNot { it.deleted }
    }

    /** Ответ сервера на отправку — сразу в историю и замеры, не дожидаясь обновления. */
    private fun remember(server: SyncRecord) {
        val c = cache
        val next = when (server) {
            is Workout -> c.copy(workouts = merged(c.workouts, listOf(server)).sortedByDescending { it.startedAt })
            is Measurement -> c.copy(measurements = merged(c.measurements, listOf(server)).sortedByDescending { it.measuredAt })
            is SetFact -> return
        }
        saveCache(withUnsent(next))
    }

    /** Замер виден в «Раньше» сразу, ещё до отправки. */
    fun rememberMeasurements(ms: List<Measurement>) = saveCache(cache.copy(measurements = ms + cache.measurements))

    /** Закрытая тренировка — в историю до следующего обновления. */
    fun rememberWorkout(w: Workout) = saveCache(cache.copy(workouts = listOf(w) + cache.workouts.filterNot { it.id == w.id }))

    // Отправка очереди

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun poke() {
        wakeUp.trySend(Unit)
    }

    fun setPaused(on: Boolean) {
        paused = on
        storage.write(PAUSE_KEY, if (on) "1" else "0")
        poke()
    }

    /** Убрать из очереди запись, которую сервер отверг: иначе за ней встанет всё остальное. */
    fun dropRejected() {
        if (rejected == null) return
        rejected = null
        act { copy(outbox = outbox.copy(pending = outbox.pending.drop(1))) }
        poke()
    }

    private suspend fun syncLoop() {
        var backoff = 2
        while (true) {
            val s = session
            val p = s?.outbox?.next()
            val k = key
            if (k == null || paused || p == null || rejected != null) {
                wakeUp.receive()
                continue
            }
            // Сначала /me: чей это ключ. Без этого очередь могла бы уйти чужому (ADR 0008)
            val o = owner
            if (o?.first != k) {
                try {
                    val me = client(k).me()
                    if (key == k) adopt(me, k)
                } catch (e: Throwable) {
                    link = linkOf(e).let { if (it == Link.ONLINE) Link.SERVER_ERROR else it }
                    withTimeoutOrNull(backoff.seconds) { wakeUp.receive() }
                    backoff = (backoff * 2).coerceAtMost(60)
                }
                continue
            }
            if (s.userId != o.second) {
                link = Link.OTHER_USER
                wakeUp.receive()
                continue
            }
            when (val r = client(k).send(p)) {
                is SendResult.Accepted -> {
                    act { accepted(p, r.server) }
                    remember(r.server)
                    link = Link.ONLINE
                    backoff = 2
                }
                is SendResult.Conflict -> {
                    act { conflict(r.server, r.serverOp) }
                    remember(r.server)
                    link = Link.ONLINE
                }
                is SendResult.Rejected -> {
                    rejected = r
                    link = Link.ONLINE
                }
                is SendResult.Failed -> {
                    link = r.link
                    withTimeoutOrNull(backoff.seconds) { wakeUp.receive() }
                    backoff = (backoff * 2).coerceAtMost(60)
                }
            }
        }
    }

    // Картинки: с ключом через HTTP-клиент, в памяти на время работы; на диске их держит service worker

    val images = mutableStateMapOf<String, ImageBitmap?>()

    fun image(mediaKey: String) {
        val name = "$mediaKey.jpg"
        if (name in images) return
        images[name] = null
        scope.launch {
            runCatching { Image.makeFromEncoded(api.media(name)).toComposeImageBitmap() }
                .onSuccess { images[name] = it }
                .onFailure { images.remove(name) }
        }
    }

    // История: разбор с сервера, в памяти

    val comparisons = mutableStateMapOf<Uuid, Comparison>()

    fun loadComparison(id: Uuid) {
        if (id in comparisons) return
        scope.launch { runCatching { api.compare(id) }.onSuccess { comparisons[id] = it }.onFailure { link = linkOf(it) } }
    }

    fun start() {
        window.history.replaceState(0.toJsNumber(), "")
        window.addEventListener("popstate") { onPop() }
        window.addEventListener("online") { poke(); refresh(force = false) }
        document.addEventListener("visibilitychange") {
            if (pageVisible()) {
                poke()
                refresh(force = false)
            }
        }
        scope.launch { syncLoop() }
        if (session?.active != null) {
            wake(true)
            go(Screen.Workout)
        } else if (session?.workout != null) {
            go(Screen.Finish)
        }
        refresh()
    }

    fun today(): LocalDate = Clock.System.now().date()
}

fun linkText(link: Link): String? = when (link) {
    Link.ONLINE -> null
    Link.OFFLINE -> "офлайн"
    Link.ACCESS_LOGIN -> "нужен вход в Cloudflare"
    Link.KEY_REJECTED -> "ключ не принят"
    Link.SERVER_ERROR -> "ошибка сервера"
    Link.OTHER_USER -> "ключ другого пользователя"
}
