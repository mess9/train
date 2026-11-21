package train.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import train.client.ApiClient
import train.client.ApiException
import train.client.Link
import train.client.PutResult
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
import train.core.PlanPut
import train.core.Profile
import train.core.RestTimer
import train.core.Tools
import train.core.Session
import train.core.SetFact
import train.core.SyncRecord
import train.core.TrainJson
import train.core.Workout
import train.core.WorkoutLayout
import train.core.WorkoutVolume
import train.core.accepted
import train.core.conflict
import train.core.loadSession
import train.core.measure
import train.core.moveItem
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
    /** Профиль — ради настроек телефона (вибрация); сам профиль правят через `gym profile`. */
    val profile: Profile? = null,
)

const val KEY_KEY = "key"
const val CACHE_KEY = "cache"
const val PAUSE_KEY = "pause"

/** Адрес сервера и сервисный токен Cloudflare Access — только у APK ([serverConfigurable]). */
const val SERVER_KEY = "server"
const val CF_ID_KEY = "cf_client_id"
const val CF_SECRET_KEY = "cf_client_secret"

/** Ссылка `https://сервер/#key=…` из `gym admin key --qr` — адрес и ключ; просто ключ — адреса нет. */
fun parseKeyLink(text: String): Pair<String?, String> {
    val t = text.trim()
    val i = t.indexOf("#key=")
    if (i < 0) return null to t
    val origin = t.substring(0, i).trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") }
    return origin to t.substring(i + "#key=".length).substringBefore('&').trim()
}

class AppState(private val scope: CoroutineScope) {
    private val storage = DeviceStorage
    private val http = httpClient()

    var key by mutableStateOf(storage.read(KEY_KEY))
        private set
    val api = ApiClient(http, serverOrigin(), key)

    /** Клиент с этим ключом: ответ точно относится к нему, даже если ключ тем временем сменили. */
    private fun client(k: String) = ApiClient(http, serverOrigin(), k)

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

    var rest by mutableStateOf<RestTimer?>(null)
    var tools by mutableStateOf(Tools())

    /** Подход, отмеченный последним: (упражнение раскладки, номер). Держит строку в свёрнутой карточке. */
    var justRated by mutableStateOf<Pair<Uuid, Int>?>(null)

    // Навигация: стек экранов в такт истории браузера — «назад» на Android работает как в приложении.
    // Каждая запись истории помнит свою глубину; popstate сверяет стек с ней. Счётчика «пропустить
    // следующий popstate» нет: потерянное или лишнее событие не съедает следующее «назад».

    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
        private set

    val screen get() = stack.last()

    private val depth get() = stack.size - 1


    fun go(s: Screen) {
        stack = stack + s
        BackStack.push(depth)
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
        if (from > depth) BackStack.go(depth - from)
        else if (from < depth) BackStack.push(depth)
    }

    fun back() {
        if (stack.size <= 1) return
        if (BackStack.depth() == depth) {
            BackStack.back()
        } else {
            // История разошлась со стеком — не ждём popstate, которого может не быть
            stack = stack.dropLast(1)
            BackStack.replace(depth)
        }
    }

    private fun onPop() {
        val d = BackStack.depth()
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
        val now = Clock.System.now()
        // Смещение пояса телефона: по нему сервер считает день тренировки
        act { start(plan, layout, exercises, previous, now, TimeZone.currentSystemDefault().offsetAt(now)) }
        rest = null
        wake(true)
        // На тренировке «назад» ведёт на главную, а не в план; шаг истории есть и без плана
        root(Screen.Workout)
    }

    fun exercise(id: Uuid): Exercise? = session?.exercise(id) ?: cache.exercises.firstOrNull { it.id == id }

    fun plan(id: Uuid?): Plan? = id?.let { pid -> cache.plans.firstOrNull { it.id == pid } }

    fun workoutTitle(w: Workout?): String = plan(w?.planId)?.title ?: "Тренировка"

    // Подключение

    /** Ключ или ссылка `…/#key=…`. У APK ссылка задаёт и сервер; у PWA сервер — страница, адрес из ссылки не нужен. */
    fun connect(newKey: String) {
        val (linkOrigin, k) = parseKeyLink(newKey)
        if (k.isEmpty()) return
        val origin = (if (serverConfigurable) linkOrigin else null) ?: serverOrigin()
        if (origin.isEmpty()) {
            connectError = "Нужна ссылка с адресом сервера: https://…/#key=…"
            return
        }
        connectError = null
        scope.launch {
            // Общий клиент не трогаем, пока ключ не проверен
            val me = runCatching { ApiClient(http, origin, k).me() }.getOrElse { e ->
                link = linkOf(e)
                connectError = linkText(link) ?: (e.message ?: "ошибка")
                return@launch
            }
            if (serverConfigurable) storage.write(SERVER_KEY, origin)
            api.baseUrl = origin
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

    /** Сервисный токен Cloudflare Access (APK): пусто — без токена. Действует со следующего запроса. */
    fun setAccess(id: String, secret: String) {
        listOf(CF_ID_KEY to id, CF_SECRET_KEY to secret).forEach { (name, v) ->
            if (v.isBlank()) storage.remove(name) else storage.write(name, v.trim())
        }
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
                val profile = runCatching { c.profile() }.getOrNull() ?: cache.profile
                if (key != k) return@launch
                saveCache(withUnsent(Cache(me, plans, exercises, lastSets, workouts, measurements, now, profile)))
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

    /** Вибрация в конце отдыха и таймера (`Profile.vibration`, по умолчанию выключена). */
    val vibration get() = cache.profile?.vibration == true

    /** Не сохранилась настройка профиля — почему. */
    var profileError by mutableStateOf<String?>(null)
        private set

    /** Включить или выключить вибрацию. */
    fun setVibration(on: Boolean) = updateProfile { it.copy(vibration = on) }

    /** Рост, см: агент видит его рядом с весом тела. `null` — стереть. */
    fun setHeight(cm: Double?) = updateProfile { it.copy(heightCm = cm) }

    /** Дата рождения: возраст для процента жира по складкам. */
    fun setBirthDate(date: LocalDate?) = updateProfile { it.copy(birthDate = date) }

    /**
     * Поменять профиль: сразу на экране, потом на сервере. Профиль меняется целиком, поэтому
     * сначала свежая его версия — имя и оборудование из `gym profile` не затираются. Сервер не
     * принял — настройка возвращается.
     */
    private fun updateProfile(change: (Profile) -> Profile) {
        val k = key ?: return
        val before = cache.profile
        profileError = null
        saveCache(cache.copy(profile = change(before ?: Profile())))
        scope.launch {
            try {
                val c = client(k)
                val saved = c.putProfile(change(c.profile()))
                if (key == k) saveCache(cache.copy(profile = saved))
            } catch (e: Throwable) {
                if (key != k) return@launch
                saveCache(cache.copy(profile = before))
                profileError = "Не сохранилось: " + (linkText(linkOf(e)) ?: e.message ?: "ошибка сервера")
            }
        }
    }

    /** Не сохранился новый порядок упражнений в плане — почему. Экран плана гасит при уходе. */
    var planError by mutableStateOf<String?>(null)

    /** Планы, чей порядок сейчас уходит на сервер, и их версия до правок телефона. */
    private val planSaves = HashMap<Uuid, Plan>()

    private fun putPlanCache(p: Plan) = saveCache(cache.copy(plans = cache.plans.map { if (it.id == p.id) p else it }))

    /**
     * Переставить упражнение в блоке плана: сразу на экране, потом новой версией на сервере.
     * В кэше `version` — версия сервера, от неё и идёт правка. Перестановки подряд уходят по
     * одной: пока ждём ответа, следующая копится в кэше и уходит поверх принятой версии.
     */
    fun movePlanItem(planId: Uuid, blockId: Uuid, from: Int, to: Int) {
        val k = key ?: return
        val plan = plan(planId) ?: return
        val body = plan.body.moveItem(blockId, from, to)
        if (body == plan.body) return
        planError = null
        putPlanCache(plan.copy(body = body))
        if (planId in planSaves) return
        planSaves[planId] = plan
        scope.launch { savePlan(planId, k) }
    }

    /**
     * Отправлять порядок, пока кэш не совпадёт с принятым. Агент успел поменять план — берём его
     * версию, порядок не сохраняется. Нет связи — план возвращается к версии сервера.
     */
    private suspend fun savePlan(id: Uuid, k: String) {
        try {
            while (true) {
                val p = plan(id) ?: return
                val r = client(k).putPlan(id, PlanPut(p.date, p.title, p.body, baseVersion = p.version))
                if (key != k) return
                when (r) {
                    is PutResult.Accepted -> {
                        planSaves[id] = r.record
                        val now = plan(id) ?: return
                        if (now.body == r.record.body) return putPlanCache(r.record)
                        // Пока шёл запрос, переставили ещё — та же правка поверх новой версии
                        putPlanCache(r.record.copy(body = now.body))
                    }
                    is PutResult.Conflict -> {
                        putPlanCache(r.current)
                        planError = if (r.current.startedAt != null) "План уже начат — порядок не меняется"
                        else "Агент успел поправить план — порядок не сохранён, вот новая версия"
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            if (key != k) return
            planSaves[id]?.let(::putPlanCache)
            planError = "Порядок не сохранился: " + (linkText(linkOf(e)) ?: e.message ?: "ошибка сервера")
        } finally {
            planSaves.remove(id)
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

    /** Замер виден в «Раньше» сразу, ещё до отправки; исправленный заменяет прежний. */
    fun rememberMeasurements(ms: List<Measurement>) {
        val ids = ms.map { it.id }.toSet()
        saveCache(cache.copy(measurements = ms + cache.measurements.filterNot { it.id in ids }))
    }

    /** Замер — в очередь и сразу на экран. */
    fun saveMeasurements(ms: List<Measurement>) {
        if (ms.isEmpty()) return
        act { ms.fold(this) { s, m -> s.measure(m) } }
        rememberMeasurements(ms)
    }

    /** Закрытая тренировка — в историю до следующего обновления. */
    fun rememberWorkout(w: Workout) = saveCache(cache.copy(workouts = listOf(w) + cache.workouts.filterNot { it.id == w.id }))

    // Отправка очереди

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun poke() {
        wakeUp.trySend(Unit)
    }

    fun pauseSending(on: Boolean) {
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
            runCatching { decodeImage(api.media(name)) }
                .onSuccess { images[name] = it }
                .onFailure { images.remove(name) }
        }
    }

    /** Сводка за период и фото прогресса — раздел «Замеры». */
    val progress = Progress(scope, api, { link = it }) { id -> session?.outbox?.pending?.any { it.record is Workout && it.record.id == id } == true }

    // История: разбор с сервера, в памяти

    val comparisons = mutableStateMapOf<Uuid, Comparison>()

    fun loadComparison(id: Uuid) {
        if (id in comparisons) return
        scope.launch { runCatching { api.compare(id) }.onSuccess { comparisons[id] = it }.onFailure { link = linkOf(it) } }
    }

    /** Объём тренировок для списка истории: считает сервер, у телефона нет всех подходов. */
    val volumes = mutableStateMapOf<Uuid, WorkoutVolume>()

    fun loadVolumes() {
        val k = key ?: return
        scope.launch {
            // Сервер без этого запроса (старая сборка) — список просто без объёма
            runCatching { client(k).volumes(Clock.System.now().date().minus(DatePeriod(days = 365)), null) }
                .onSuccess { vs -> if (key == k) volumes.putAll(vs.associateBy { it.workoutId }) }
        }
    }

    /** Тренировка ждёт отправки — она сама или её подходы: стирать нельзя, очередь её воскресит. */
    fun unsent(workoutId: Uuid): Boolean = session?.outbox?.pending.orEmpty().any {
        val r = it.record
        (r is Workout && r.id == workoutId) || (r is SetFact && r.workoutId == workoutId)
    }

    /** Идёт удаление; `erasing` и `eraseError` — для листа подтверждения. */
    var erasing by mutableStateOf(false)
        private set
    var eraseError by mutableStateOf<String?>(null)

    /**
     * Стереть тренировку насовсем — и на сервере (подходы, их версии), мимо корзины. Только по
     * сети: в очередь не ставится, иначе пришлось бы держать тренировку в истории до отправки.
     */
    fun eraseWorkout(id: Uuid, onDone: () -> Unit) {
        val k = key ?: return
        if (erasing) return
        if (unsent(id)) {
            eraseError = "Тренировка ещё не отправлена на сервер — дождись отправки и попробуй снова"
            return
        }
        erasing = true
        eraseError = null
        scope.launch {
            try {
                client(k).eraseWorkout(id)
                forget(id)
                onDone()
                refresh()
            } catch (e: Throwable) {
                // Уже стёрта (с другого телефона) — всё равно убрать отсюда
                if (e is ApiException && e.status == 404) {
                    forget(id)
                    onDone()
                } else {
                    link = linkOf(e)
                    eraseError = if (link == Link.ONLINE) "Сервер не удалил: ${e.message.orEmpty()}" else "Нет связи с сервером — удалить можно только онлайн"
                }
            } finally {
                erasing = false
            }
        }
    }

    /** Стёртую тренировку — из истории, разбора и объёмов; замеры и фото остаются без привязки. */
    private fun forget(id: Uuid) {
        comparisons.remove(id)
        volumes.remove(id)
        saveCache(
            cache.copy(
                workouts = cache.workouts.filterNot { it.id == id },
                measurements = cache.measurements.map { if (it.workoutId == id) it.copy(workoutId = null) else it },
            ),
        )
        progress.unlinkWorkout(id)
    }

    fun start() {
        BackStack.replace(0)
        BackStack.onPop { onPop() }
        onOnline { poke(); refresh(force = false); progress.retry() }
        onVisible {
            poke()
            refresh(force = false)
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
    Link.ACCESS_LOGIN -> if (serverConfigurable) "Cloudflare не пускает — проверьте сервисный токен" else "нужен вход в Cloudflare"
    Link.KEY_REJECTED -> "ключ не принят"
    Link.SERVER_ERROR -> "ошибка сервера"
    Link.OTHER_USER -> "ключ другого пользователя"
}
