package train.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val API_PREFIX = "/api"

/** Настройки JSON для всех сторон: незнакомые поля не ломают старого клиента. */
val TrainJson = Json { ignoreUnknownKeys = true }

/** Ответ `GET /api/info`: проверка, что клиент достучался до своего сервера. */
@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
)
