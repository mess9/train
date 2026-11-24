package train.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import train.core.ExerciseHistory
import train.core.RecordType
import train.core.TrainJson
import train.core.newId
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    @Test
    fun passesExerciseAndHundredRecentAndReadsEmptyHistory() = runBlocking {
        val id = newId()
        val history = ExerciseHistory(id, "Пустое упражнение", RecordType.TIME, sessions = 0, recent = emptyList())
        val engine = MockEngine { request ->
            assertEquals("/api/history", request.url.encodedPath)
            assertEquals(id.toString(), request.url.parameters["ids"])
            assertEquals("100", request.url.parameters["recent"])
            respond(TrainJson.encodeToString(listOf(history)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { http ->
            assertEquals(listOf(history), ApiClient(http, "https://gym.test", "key").history(listOf(id), recent = 100))
        }
    }
}
