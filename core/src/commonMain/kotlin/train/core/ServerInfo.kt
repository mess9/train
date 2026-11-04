package train.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val API_PREFIX = "/api"

/** Ключ API (ADR 0008). */
const val API_KEY_HEADER = "X-Api-Key"

/** В ответе `409` — `opId` текущей версии записи (ADR 0007). */
const val OP_ID_HEADER = "X-Op-Id"

/**
 * Настройки JSON для всех сторон: незнакомые поля не ломают старого клиента. Отсутствующее
 * поле — `null`, `null` в вывод не пишется: неизвестное не выдумываем, а ответ короче для агента.
 */
val TrainJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * [build] — номер сборки Jenkins и коммит, `dev` вне Jenkins. [app] — хеш сборки PWA, которую
 * сервер отдаёт сейчас: приложение сравнивает его со своим и видит, что пора перезапуститься.
 */
@Serializable
data class ServerInfo(val name: String, val version: String, val build: String? = null, val app: String? = null)
