package train.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import train.core.API_PREFIX
import train.core.ServerInfo
import train.core.TrainJson

/** Клиент API для CLI и приложения. Движок HTTP выбирает вызывающий: Java в CLI, fetch в браузере. */
class ApiClient(
    private val http: HttpClient,
    baseUrl: String,
) {
    private val api = baseUrl.trimEnd('/') + API_PREFIX

    suspend fun info(): ServerInfo = TrainJson.decodeFromString(http.get("$api/info").bodyAsText())
}
