package train.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import train.core.Records
import train.core.newId
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordsTest {
    @Test
    fun passesIdsAndExcludeAndReadsEmptyHistory() = runBlocking {
        val ids = listOf(newId(), newId())
        val exclude = newId()
        val engine = MockEngine { request ->
            assertEquals("/api/records", request.url.encodedPath)
            assertEquals(ids.joinToString(","), request.url.parameters["ids"])
            assertEquals(exclude.toString(), request.url.parameters["exclude"])
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { http ->
            assertEquals(Records(), ApiClient(http, "https://gym.test", "key").records(ids, exclude))
        }
    }
}
