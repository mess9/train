package train.cli

import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import train.core.Block
import train.core.BlockItem
import train.core.BlockRole
import train.core.Exercise
import train.core.LayoutBlock
import train.core.LayoutItem
import train.core.Measurement
import train.core.Plan
import train.core.PlanBody
import train.core.PlanPut
import train.core.PlanStatus
import train.core.RecordType
import train.core.SetFact
import train.core.SyncPut
import train.core.SyncRecord
import train.core.TargetSet
import train.core.TrainJson
import train.core.Workout
import train.core.WorkoutDetail
import train.core.WorkoutLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Сервер в памяти: те же правила, что у `sync` (ADR 0007) и `PUT /plans` — новая запись `rev = 1`,
 * тот же `opId` — без изменений, чужой `baseRev` — `409`, начатый план не меняется.
 */
private class FakeServer(val exercises: Set<Uuid>) {
    val plans = mutableMapOf<Uuid, Plan>()
    val workouts = mutableMapOf<Uuid, Workout>()
    val sets = mutableMapOf<Uuid, SetFact>()
    val measurements = mutableMapOf<Uuid, Measurement>()
    private val ops = mutableMapOf<Uuid, Uuid>()
    val writes = mutableListOf<String>()

    fun reply(req: HttpRequestData): Pair<HttpStatusCode, String> {
        val path = req.url.encodedPath.removePrefix("/api")
        val parts = path.trim('/').split('/')
        val body = (req.body as? TextContent)?.text
        val id = parts.getOrNull(1)?.let(Uuid::parse)
        if (req.method == HttpMethod.Get) {
            return when {
                path == "/exercises" -> ok(ListSerializer(Exercise.serializer()), exercises.map { Exercise(it, "у") })
                parts[0] == "plans" -> found(Plan.serializer(), plans[id])
                parts[0] == "workouts" -> found(WorkoutDetail.serializer(), workouts[id]?.let { w ->
                    WorkoutDetail(w, sets.values.filter { it.workoutId == w.id }, measurements.values.filter { it.workoutId == w.id })
                })
                parts[0] == "measurements" -> found(Measurement.serializer(), measurements[id])
                else -> HttpStatusCode.NotFound to "{}"
            }
        }
        writes += path
        return when {
            parts[0] == "plans" -> {
                val put = TrainJson.decodeFromString(PlanPut.serializer(), body!!)
                val old = plans[id!!]
                if (old?.startedAt != null) return HttpStatusCode.Conflict to TrainJson.encodeToString(Plan.serializer(), old)
                val plan = Plan(id, put.date, put.title, (old?.version ?: 0) + 1, put.body, PlanStatus.PLANNED)
                ok(Plan.serializer(), plan.also { plans[id] = it })
            }
            parts[0] == "workouts" && parts.size == 4 -> sync(SetFact.serializer(), sets, body!!) { it }
            parts[0] == "workouts" -> sync(Workout.serializer(), workouts, body!!) { w ->
                w.planId?.let { p -> plans[p]?.let { plans[p] = it.copy(status = PlanStatus.STARTED, startedAt = w.startedAt) } }
                w
            }
            parts[0] == "measurements" -> sync(Measurement.serializer(), measurements, body!!) { it }
            else -> HttpStatusCode.NotFound to "{}"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : SyncRecord> sync(s: KSerializer<T>, store: MutableMap<Uuid, T>, body: String, onWrite: (T) -> T): Pair<HttpStatusCode, String> {
        val put = TrainJson.decodeFromString(SyncPut.serializer(s), body)
        val cur = store[put.data.id]
        if (cur != null && ops[cur.id] == put.opId) return ok(s, cur)
        if (cur != null && cur.rev != put.baseRev) return HttpStatusCode.Conflict to TrainJson.encodeToString(s, cur)
        val next = onWrite(put.data.withRev((cur?.rev ?: 0) + 1) as T)
        store[next.id] = next
        ops[next.id] = put.opId
        return ok(s, next)
    }

    private fun <T> ok(s: KSerializer<T>, v: T) = HttpStatusCode.OK to TrainJson.encodeToString(s, v)

    private fun <T : Any> found(s: KSerializer<T>, v: T?) =
        if (v == null) HttpStatusCode.NotFound to """{"error":"не найдено"}""" else ok(s, v)
}

class ImportTest {
    private val noConfig = Files.createTempDirectory("gym").resolve("none")
    private val env = mapOf("GYM_API_KEY" to "k", "GYM_CONFIG" to noConfig.toString())

    private val squat = importId("test", "ex-squat")
    private val planId = importId("test", "plan-1")
    private val blockId = importId("test", "block-1")
    private val itemId = importId("test", "item-1")
    private val targetId = importId("test", "target-1")
    private val workoutId = importId("test", "workout-1")
    private val start = Instant.parse("2026-10-15T12:24:02Z")

    private val plan = PlanPut(
        LocalDate(2026, 10, 15), "Среда",
        PlanBody(listOf(Block(blockId, BlockRole.MAIN, items = listOf(BlockItem(itemId, squat, sets = listOf(TargetSet(targetId, weight = 15.0, repsMin = 10)))))))
    )
    private val workout = Workout(
        id = workoutId, planId = planId, startedAt = start, utcOffset = UtcOffset(hours = 4),
        finishedAt = Instant.parse("2026-10-15T13:49:42Z"), comment = "импорт",
        layout = WorkoutLayout(listOf(LayoutBlock(blockId, BlockRole.MAIN, items = listOf(LayoutItem(itemId, squat, RecordType.WEIGHT_REPS, false, plan.body.blocks[0].items[0].sets))))),
    )
    private fun set(n: Int, reps: Int) = SetFact(
        importId("test", "set-$n"), workoutId, blockId, itemId, n, targetId.takeIf { n == 1 }, squat, RecordType.WEIGHT_REPS,
        weight = 15.0, reps = reps, done = true, doneAt = Instant.parse("2026-10-15T12:3$n:00Z"),
    )
    private val weight = Measurement(importId("test", "weight-1"), "weight", 58.0, "kg", Instant.parse("2026-10-08T08:00:00Z"))

    private fun dir(sets: List<SetFact> = listOf(set(1, 10), set(2, 12))): Path {
        val root = Files.createTempDirectory("import")
        fun write(sub: String, name: String, text: String) {
            Files.createDirectories(root.resolve(sub))
            Files.writeString(root.resolve(sub).resolve(name), text)
        }
        // План — как его выдаёт `plan get`: с полями вывода
        write("plans", "p.json", TrainJson.encodeToString(Plan.serializer(), Plan(planId, plan.date, plan.title, 3, plan.body, PlanStatus.STARTED)))
        write("workouts", "w.json", TrainJson.encodeToString(WorkoutDetail.serializer(), WorkoutDetail(workout, sets)))
        write("measurements", "m.json", TrainJson.encodeToString(ListSerializer(Measurement.serializer()), listOf(weight)))
        return root
    }

    private fun gym(server: FakeServer, args: String): CliktCommandTestResult {
        val engine = MockEngine { req ->
            val (status, body) = server.reply(req)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val cmd = gym { HttpClient(engine) }
        return cmd.test(args.split(' '), envvars = env) { runBlocking { cmd.parse(it) } }
    }

    private fun report(r: CliktCommandTestResult) = TrainJson.decodeFromString(ImportReport.serializer(), r.stdout)

    @Test
    fun uuid5MatchesRfc() {
        // Python: uuid.uuid5(uuid.NAMESPACE_DNS, "python.org")
        assertEquals(Uuid.parse("886313e1-3b8a-5372-9b90-0c9aee199e5d"), uuid5(Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "python.org"))
        // То же пространство имён и в конвертерах вне репозитория: uuid5(IMPORT_NAMESPACE, "hevy:dd142a1e-…")
        assertEquals(importId("hevy", "x"), uuid5(IMPORT_NAMESPACE, "hevy:x"))
    }

    @Test
    fun importsInOrderAndRepeatIsNoop() {
        val server = FakeServer(setOf(squat))
        val dir = dir()
        val first = gym(server, "import $dir")
        assertEquals(0, first.statusCode, first.stderr)
        val ws = "/workouts/$workoutId"
        assertEquals(
            listOf("/plans/$planId", ws, "$ws/sets/${set(1, 0).id}", "$ws/sets/${set(2, 0).id}", "/measurements/${weight.id}"),
            server.writes,
        )
        assertEquals(5, report(first).created)
        // Версию плана тренировка берёт у сервера; план после неё начат
        assertEquals(1, server.workouts[workoutId]!!.planVersion)
        assertEquals(PlanStatus.STARTED, server.plans[planId]!!.status)

        val again = gym(server, "import $dir")
        assertEquals(0, again.statusCode, again.stderr)
        assertEquals(5, report(again).same)
        assertEquals(5, server.writes.size)
    }

    @Test
    fun differingRecordIsKeptUntilUpdate() {
        val server = FakeServer(setOf(squat))
        gym(server, "import ${dir()}")
        // Владелец поправил подход на телефоне — rev 2
        val edited = server.sets.getValue(set(2, 0).id).let { it.copy(reps = 11, rev = 2) }
        server.sets[edited.id] = edited

        val r = gym(server, "import ${dir()}")
        assertEquals(1, r.statusCode)
        assertTrue("--update" in r.stderr, r.stderr)
        assertEquals(listOf(edited.id), report(r).records.filter { it.action == ImportAction.differs }.map { it.id })
        assertEquals(11, server.sets.getValue(edited.id).reps)

        val dry = gym(server, "import ${dir()} --update --dry-run")
        assertEquals(0, dry.statusCode, dry.stderr)
        assertEquals(1, report(dry).updated)
        assertEquals(11, server.sets.getValue(edited.id).reps)

        val writes = server.writes.size
        val forced = gym(server, "import ${dir()} --update")
        assertEquals(0, forced.statusCode, forced.stderr)
        assertEquals(12, server.sets.getValue(edited.id).reps)
        assertEquals(3, server.sets.getValue(edited.id).rev)
        assertEquals(writes + 1, server.writes.size)
    }

    @Test
    fun startedPlanIsNotChanged() {
        val server = FakeServer(setOf(squat))
        gym(server, "import ${dir()}")
        server.plans[planId] = server.plans.getValue(planId).copy(title = "Другой")
        val r = gym(server, "import ${dir()} --update")
        assertEquals(1, r.statusCode)
        val rec = report(r).records.single { it.kind == "plan" }
        assertEquals(ImportAction.differs, rec.action)
        assertEquals("Другой", server.plans.getValue(planId).title)
    }

    @Test
    fun dryRunWritesNothing() {
        val server = FakeServer(setOf(squat))
        val r = gym(server, "import ${dir()} --dry-run")
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(report(r).dryRun)
        assertEquals(5, report(r).created)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun unknownExerciseStopsBeforeWrites() {
        val server = FakeServer(emptySet())
        val r = gym(server, "import ${dir()}")
        assertEquals(1, r.statusCode)
        assertTrue("нет в справочнике: $squat" in r.stderr, r.stderr)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun badFilesStopBeforeWrites() {
        val server = FakeServer(setOf(squat))
        // Подход ссылается на строку, которой нет в раскладке
        val stray = set(1, 10).copy(itemId = importId("test", "nope"))
        val r = gym(server, "import ${dir(listOf(stray))}")
        assertEquals(1, r.statusCode)
        assertTrue("нет такого блока" in r.stderr, r.stderr)

        val typo = dir().also { Files.writeString(it.resolve("measurements/m.json"), """{"id":"${weight.id}","type":"weight","valu":1}""") }
        val r2 = gym(server, "import $typo")
        assertEquals(1, r2.statusCode)
        assertTrue("'valu'" in r2.stderr, r2.stderr)
        assertTrue(server.writes.isEmpty())
    }
}
