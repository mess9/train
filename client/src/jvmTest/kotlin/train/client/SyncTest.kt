package train.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import train.core.OP_ID_HEADER
import train.core.Pending
import train.core.SyncPut
import train.core.TrainJson
import train.core.Workout
import train.core.WorkoutLayout
import train.core.newId
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SyncTest {
    private val w = Workout(newId(), startedAt = Instant.parse("2026-11-03T10:00:00Z"), layout = WorkoutLayout(emptyList()), rev = 3)
    private val pending = Pending(w, newId())
    private val requests = mutableListOf<HttpRequestData>()

    private fun send(op: String? = null, reply: () -> Pair<HttpStatusCode, String>): SendResult {
        val engine = MockEngine { req ->
            requests += req
            val (status, body) = reply()
            val headers = listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), op?.let { OP_ID_HEADER to listOf(it) })
            respond(body, status, headersOf(*headers.toTypedArray()))
        }
        return runBlocking { ApiClient(HttpClient(engine), "https://gym.test", "k").send(pending) }
    }

    private fun json(x: Workout) = TrainJson.encodeToString(Workout.serializer(), x)

    @Test
    fun putsRecordWithBaseRevAndOpId() {
        val r = send { HttpStatusCode.OK to json(w.copy(rev = 4)) }
        assertEquals(SendResult.Accepted(w.copy(rev = 4)), r)
        val req = requests.single()
        assertEquals("/api/workouts/${w.id}", req.url.encodedPath)
        val body = TrainJson.decodeFromString(SyncPut.serializer(Workout.serializer()), (req.body as TextContent).text)
        assertEquals(SyncPut(w, 3, pending.opId), body)
    }

    @Test
    fun conflictCarriesServerVersion() {
        assertEquals(SendResult.Conflict(w.copy(rev = 9)), send { HttpStatusCode.Conflict to json(w.copy(rev = 9)) })
        val op = newId()
        assertEquals(SendResult.Conflict(w.copy(rev = 9), op), send(op.toString()) { HttpStatusCode.Conflict to json(w.copy(rev = 9)) })
    }

    @Test
    fun statusesMapToLink() {
        assertEquals(SendResult.Failed(Link.ACCESS_LOGIN), send { HttpStatusCode.Found to "" })
        assertEquals(SendResult.Failed(Link.KEY_REJECTED), send { HttpStatusCode.Unauthorized to """{"error":"нет ключа"}""" })
        assertEquals(SendResult.Failed(Link.SERVER_ERROR), send { HttpStatusCode.BadGateway to "" })
        assertEquals(SendResult.Rejected(400, "нет в справочнике"), send { HttpStatusCode.BadRequest to """{"error":"нет в справочнике"}""" })
        assertEquals(SendResult.Failed(Link.OFFLINE), send { throw IOException("нет сети") })
    }

    @Test
    fun opaqueRedirectIsAccessLogin() {
        assertEquals(Link.ACCESS_LOGIN, linkOf(ApiException(0, "")))
    }
}
