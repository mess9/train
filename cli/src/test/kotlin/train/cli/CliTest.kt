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
import kotlinx.serialization.json.jsonObject
import train.core.KeyCreated
import train.core.Plan
import train.core.PlanBody
import train.core.PlanStatus
import train.core.TrainJson
import train.core.newId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CliTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val noConfig = Files.createTempDirectory("gym").resolve("none")
    private val env = mapOf("GYM_API_KEY" to "k", "GYM_CONFIG" to noConfig.toString())

    /** Запустить `gym` с подставным сервером: [reply] — статус и тело ответа. */
    private fun gym(
        args: String,
        env: Map<String, String> = this.env,
        reply: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" },
    ): CliktCommandTestResult {
        val engine = MockEngine { req ->
            requests += req
            val (status, body) = reply(req)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val cmd = gym { HttpClient(engine) }
        return cmd.test(args.split(' '), envvars = env) { runBlocking { cmd.parse(it) } }
    }

    private fun HttpRequestData.text() = (body as? TextContent)?.text

    private fun file(text: String): Path = Files.createTempFile("gym", ".json").also { Files.writeString(it, text) }

    private val plan = Plan(newId(), LocalDate(2026, 11, 4), "Грудь", 2, PlanBody(emptyList()), PlanStatus.PLANNED)

    @Test
    fun planPutTakesIdFromFile() {
        // Вывод `plan get` можно поправить и отправить обратно: лишние поля не мешают
        val input = file(TrainJson.encodeToString(Plan.serializer(), plan))
        val r = gym("plan put $input") { HttpStatusCode.OK to TrainJson.encodeToString(Plan.serializer(), plan) }

        assertEquals(0, r.statusCode, r.stderr)
        val req = requests.single()
        assertEquals(HttpMethod.Put, req.method)
        assertEquals("/api/plans/${plan.id}", req.url.encodedPath)
        assertEquals("k", req.headers["X-Api-Key"])
        val sent = TrainJson.parseToJsonElement(req.text()!!).jsonObject
        assertEquals(setOf("date", "title", "body"), sent.keys)
        assertEquals(plan, TrainJson.decodeFromString(Plan.serializer(), r.stdout))
    }

    @Test
    fun planPutWithoutIdCreatesNew() {
        val input = file("""{"date": "2026-11-04", "body": {"blocks": []}}""")
        assertEquals(0, gym("plan put $input") { HttpStatusCode.OK to TrainJson.encodeToString(Plan.serializer(), plan) }.statusCode)
        assertTrue(requests.single().url.encodedPath.startsWith("/api/plans/0"))
    }

    @Test
    fun startedPlanIsConflict() {
        val started = plan.copy(status = PlanStatus.STARTED, startedAt = Instant.parse("2026-11-04T08:00:00Z"))
        val r = gym("plan put ${file("""{"date": "2026-11-04", "body": {"blocks": []}}""")}") {
            HttpStatusCode.Conflict to TrainJson.encodeToString(Plan.serializer(), started)
        }
        assertEquals(1, r.statusCode)
        assertTrue(r.stderr.contains("\"status\":409"), r.stderr)
        assertEquals("", r.stdout)
    }

    @Test
    fun apiErrorGoesToStderr() {
        val r = gym("plan list") { HttpStatusCode.Unauthorized to """{"error":"ключ не принят"}""" }
        assertEquals(1, r.statusCode)
        assertEquals("""{"error":"ключ не принят","status":401}""", r.stderr.trim())
    }

    @Test
    fun badInputIsUsageError() {
        val r = gym("plan get not-a-uuid")
        assertEquals(1, r.statusCode)
        assertTrue(requests.isEmpty())
        assertTrue(gym("plan list --from завтра").statusCode != 0)
    }

    @Test
    fun configPrecedence() {
        val me = { _: HttpRequestData -> HttpStatusCode.OK to """{"userId":"${newId()}","email":"a@b"}""" }
        val cfg = file("# конфиг\nurl=https://file.example\nkey=file-key\ncf_client_id=id\ncf_client_secret=secret\n")
        gym("me", env = mapOf("GYM_CONFIG" to cfg.toString()), reply = me)
        gym("me", env = mapOf("GYM_CONFIG" to cfg.toString(), "GYM_API_KEY" to "env-key", "GYM_URL" to "https://env.example"), reply = me)
        gym("--url https://flag.example me", env = mapOf("GYM_CONFIG" to cfg.toString(), "GYM_URL" to "https://env.example"), reply = me)

        assertEquals(listOf("file.example", "env.example", "flag.example"), requests.map { it.url.host })
        assertEquals(listOf("file-key", "env-key", "file-key"), requests.map { it.headers["X-Api-Key"] })
        assertEquals("secret", requests.first().headers["CF-Access-Client-Secret"])
    }

    @Test
    fun garbageResponseIsError() {
        val r = gym("me") { HttpStatusCode.OK to "<html>Cloudflare</html>" }
        assertEquals(1, r.statusCode)
        assertTrue(r.stderr.startsWith("{\"error\":\"ответ сервера не разобран"), r.stderr)
    }

    @Test
    fun purgeNeedsYes() {
        assertEquals(1, gym("trash purge").statusCode)
        assertTrue(requests.isEmpty())
        gym("trash purge --yes") { HttpStatusCode.OK to """{"plans":1,"workouts":0,"measurements":0}""" }
        assertEquals(HttpMethod.Post, requests.single().method)
    }

    @Test
    fun measureAddIsNewRecord() {
        gym("measure add weight 81,5 --at 2026-11-04T07:30:00Z") { req -> HttpStatusCode.OK to req.text()!!.let { it.substringAfter("\"data\":").substringBeforeLast(",\"baseRev\"") } }
        val sent = TrainJson.parseToJsonElement(requests.single().text()!!).jsonObject
        assertEquals("0", sent["baseRev"].toString())
        val data = sent["data"]!!.jsonObject
        assertEquals("81.5", data["value"].toString())
        assertEquals("\"kg\"", data["unit"].toString())
        assertEquals("/api/measurements/${data["id"]!!.toString().trim('"')}", requests.single().url.encodedPath)
    }

    @Test
    fun searchJoinsWords() {
        gym("exercise search bench press --limit 5") { HttpStatusCode.OK to "[]" }
        assertEquals("bench press", requests.single().url.parameters["q"])
        assertEquals("5", requests.single().url.parameters["limit"])
    }

    @Test
    fun adminKeyLinkUsesFragment() {
        val k = KeyCreated(newId(), "secret-key", "телефон")
        val env = env + ("GYM_URL" to "https://train.example/")
        val r = gym("admin key --label телефон --qr", env = env) { HttpStatusCode.OK to TrainJson.encodeToString(KeyCreated.serializer(), k) }
        assertEquals(0, r.statusCode, r.stderr)
        assertTrue(r.stdout.contains("\"link\":\"https://train.example/#key=secret-key\""), r.stdout)
        assertTrue(r.stderr.contains("█"))
        assertFalse(requests.single().url.toString().contains("secret-key"))
    }
}
