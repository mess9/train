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
import kotlinx.serialization.builtins.ListSerializer
import train.core.Comparison
import train.core.Exercise
import train.core.HealthDay
import train.core.HealthExcluded
import train.core.HealthExclusion
import train.core.HealthExclusionCreate
import train.core.HealthReport
import train.core.ExerciseHistory
import train.core.JournalEntry
import train.core.JournalPut
import train.core.Profile
import train.core.Plan
import train.core.PlanBody
import train.core.PlanStatus
import train.core.EquipmentStep
import train.core.Trash
import train.core.RecordType
import train.core.Totals
import train.core.TrainJson
import train.core.newId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Текстовый вывод для агента (ADR 0014): brief, compare, history, weeks, journal. */
class AgentCliTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val env = mapOf("GYM_API_KEY" to "k", "GYM_CONFIG" to Files.createTempDirectory("gym").resolve("none").toString())
    private val bench = Exercise(newId(), "Жим")

    private fun run(vararg args: String, reply: (HttpRequestData) -> String): CliktCommandTestResult {
        val engine = MockEngine { req ->
            requests += req
            respond(reply(req), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val cmd = train.cli.gym { HttpClient(engine) }
        return cmd.test(args.toList(), envvars = env) { runBlocking { cmd.parse(it) } }
    }

    private val comparison = Comparison(
        workoutId = newId(),
        startedAt = Instant.parse("2026-11-11T10:00:00Z"),
        date = LocalDate(2026, 11, 11),
        totals = Totals(durationSec = 3600, setsPlanned = 0, setsDone = 0, setsSkipped = 0, setsAdded = 0, workSets = 0, volumeKg = 0.0, volumeUnknownSets = 0, timeUnderLoadSec = 0),
        blocks = emptyList(),
        previous = emptyList(),
    )

    @Test
    fun compareIsTextByDefault() {
        val json = TrainJson.encodeToString(Comparison.serializer(), comparison)
        val text = run("compare", comparison.workoutId.toString()) { json }
        assertEquals(0, text.statusCode, text.stderr)
        assertTrue(text.stdout.startsWith("ср 11.11.2026 · 60 мин · тренировка ${comparison.workoutId}"), text.stdout)
        val raw = run("compare", comparison.workoutId.toString(), "--json") { json }
        assertEquals(comparison, TrainJson.decodeFromString(Comparison.serializer(), raw.stdout))
    }

    @Test
    fun historyWithoutIdsAsksProgram() {
        val h = ExerciseHistory(bench.id, bench.nameRu, RecordType.WEIGHT_REPS, sessions = 0, recent = emptyList())
        val r = run("history", "--recent", "3") { TrainJson.encodeToString(ListSerializer(ExerciseHistory.serializer()), listOf(h)) }
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("/api/history", requests.single().url.encodedPath)
        assertEquals("3", requests.single().url.parameters["recent"])
        assertEquals(null, requests.single().url.parameters["ids"])
        assertEquals("Жим · вес×повторы · не делал · id ${bench.id}\n", r.stdout)
    }

    private fun entry(put: JournalPut, id: kotlin.uuid.Uuid) = TrainJson.encodeToString(
        JournalEntry.serializer(),
        JournalEntry(id, put.date, put.decision, put.reason, put.review, exerciseIds = put.exerciseIds, revises = put.revises, createdAt = Instant.parse("2026-11-11T12:00:00Z")),
    )

    @Test
    fun journalAddNeedsAllThreeKeys() {
        val old = newId()
        val r = run(
            "journal", "add", "--decision", "держать 40 кг", "--reason", "два ✕ на 10-м", "--review", "три ✓ подряд",
            "--revises", old.toString(), "--exercise", bench.id.toString(), "--date", "2026-11-11",
        ) { req ->
            if (req.method == HttpMethod.Put) {
                val put = TrainJson.decodeFromString(JournalPut.serializer(), (req.body as TextContent).text)
                assertEquals(listOf("держать 40 кг", "два ✕ на 10-м", "три ✓ подряд"), listOf(put.decision, put.reason, put.review))
                assertEquals(old, put.revises)
                entry(put, kotlin.uuid.Uuid.parse(req.url.encodedPath.substringAfterLast('/')))
            } else TrainJson.encodeToString(Exercise.serializer(), bench)
        }
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals(HttpMethod.Put, requests.first().method)
        assertTrue(r.stdout.startsWith("11.11.2026 [Жим] решение: «держать 40 кг»\n  почему: «два ✕ на 10-м»\n  пересмотр: «три ✓ подряд»\n"), r.stdout)
        assertTrue("пересматривает $old" in r.stdout, r.stdout)

        requests.clear()
        val missing = run("journal", "add", "--decision", "держать", "--reason", "так") { "{}" }
        assertEquals(1, missing.statusCode)
        assertTrue("--review" in missing.stderr, missing.stderr)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun journalListDeletedAndRestore() {
        run("journal", "list", "--deleted") { "[]" }
        assertEquals("true", requests.single().url.parameters["deleted"])
        assertEquals(null, requests.single().url.parameters["active"])
        requests.clear()
        val id = newId()
        val r = run("journal", "restore", id.toString()) {
            entry(JournalPut(LocalDate(2026, 11, 11), "a", "b", "c"), id)
        }
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/journal/$id/restore", requests.single().url.encodedPath)
    }

    @Test
    fun trashListOnly() {
        val e = JournalEntry(newId(), LocalDate(2026, 11, 11), "a", "b", "c", createdAt = Instant.parse("2026-11-11T12:00:00Z"))
        val w = TrainJson.encodeToString(Trash.serializer(), Trash(journal = listOf(e)))
        val all = TrainJson.decodeFromString(Trash.serializer(), run("trash", "list") { w }.stdout)
        assertEquals(listOf(e), all.journal)
        val plans = run("trash", "list", "--only", "plans") { w }
        assertEquals(0, plans.statusCode, plans.stderr)
        assertEquals(Trash(), TrainJson.decodeFromString(Trash.serializer(), plans.stdout))
        assertEquals(1, run("trash", "list", "--only", "кошки") { w }.statusCode)
    }

    @Test
    fun briefShowsEquipmentInAgentOrderWithExerciseNames() {
        val plan = Plan(newId(), LocalDate(2026, 11, 22), "План", 1, PlanBody(emptyList(), equipment = listOf(
            EquipmentStep(setup = "гантели 2×12,5"),
            EquipmentStep(exerciseId = bench.id, setup = "гриф 1×15"),
            EquipmentStep(exerciseId = bench.id, at = "Круг 2", setup = "доска"),
        )), PlanStatus.PLANNED)
        val r = run("brief") { req ->
            when (req.url.encodedPath) {
                "/api/profile" -> "{}"
                "/api/plans" -> TrainJson.encodeToString(ListSerializer(Plan.serializer()), listOf(plan))
                "/api/exercises/${bench.id}" -> TrainJson.encodeToString(Exercise.serializer(), bench)
                else -> "[]"
            }
        }
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue("\n  Начало → гантели 2×12,5\n  Жим → гриф 1×15\n  Круг 2 → доска" in r.stdout, r.stdout)
        assertEquals(1, requests.count { it.url.encodedPath == "/api/exercises/${bench.id}" })
    }

    @Test
    fun briefHasAllSections() {
        val r = run("brief") { req ->
            when (req.url.encodedPath) {
                "/api/profile" -> TrainJson.encodeToString(Profile.serializer(), Profile(equipment = "гантели до 15 кг"))
                else -> "[]"
            }
        }
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stdout.startsWith("## профиль\nоборудование: гантели до 15 кг\n\n## планы\n—"), r.stdout)
        // Старый сервер без /health — раздел есть, brief не падает
        assertTrue("## сон и восстановление за 28 дн.\nсервер не отдал данные сна" in r.stdout, r.stdout)
        for (s in listOf("## журнал", "## недели", "## тренировки: последняя и неразобранные", "## история упражнений программы за 30 дн.")) {
            assertTrue(s in r.stdout, s)
        }
        assertEquals(
            listOf("/api/profile", "/api/plans", "/api/journal", "/api/journal", "/api/stats/weeks", "/api/workouts", "/api/history", "/api/health"),
            requests.map { it.url.encodedPath },
        )
        val (active, recent) = requests.filter { it.url.encodedPath == "/api/journal" }
        assertEquals("true", active.url.parameters["active"])
        assertEquals(null, active.url.parameters["from"])
        assertTrue(recent.url.parameters["from"] != null)
        assertTrue(requests.single { it.url.encodedPath == "/api/history" }.url.parameters["since"] != null)
    }

    @Test
    fun healthDaysAndExclude() {
        val day = HealthDay(LocalDate(2026, 11, 15), steps = 1062)
        val report = HealthReport(LocalDate(2026, 11, 2), LocalDate(2026, 11, 15), listOf(day), sources = listOf("com.xiaomi.wearable"))
        val r = run("health", "days", "--to", "2026-11-15") { TrainJson.encodeToString(HealthReport.serializer(), report) }
        assertEquals(0, r.statusCode, r.stderr)
        assertEquals("2026-11-02", requests.single().url.parameters["from"])
        assertTrue(r.stdout.endsWith("15.11 вс шаги 1062\n"), r.stdout)

        requests.clear()
        val ex = HealthExclusion(newId(), LocalDate(2026, 3, 1), LocalDate(2026, 3, 10), "band.app", "заметка")
        val x = run("health", "exclude", "--from", "2026-03-01", "--to", "2026-03-10", "--source", "band.app", "--note", "заметка") {
            TrainJson.encodeToString(HealthExcluded.serializer(), HealthExcluded(ex, 120))
        }
        assertEquals(0, x.statusCode, x.stderr)
        assertEquals(HttpMethod.Post, requests.single().method)
        val body = TrainJson.decodeFromString(HealthExclusionCreate.serializer(), (requests.single().body as TextContent).text)
        assertEquals(HealthExclusionCreate(ex.from, ex.to, ex.source, ex.note), body)
        assertEquals("исключено 01.03.2026–10.03.2026, удалено записей: 120 · id ${ex.id}\n", x.stdout)
    }
}
