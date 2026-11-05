package train.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Разбор того, что пишет агент (план, профиль): незнакомое поле — ошибка, а не молча потерянное
 * значение. `reps` вместо `repsMin`/`repsMax` иначе дал бы подход без цели. Обмен между
 * приложением и сервером остаётся на [TrainJson] — там незнакомые поля не должны ломать старую
 * версию.
 */
val StrictJson = Json(TrainJson) { ignoreUnknownKeys = false }

/** Из текста, не из `JsonElement`: только так kotlinx называет точный путь до поля. */
fun <T> decodeStrict(serializer: DeserializationStrategy<T>, text: String): T =
    agentReadable { StrictJson.decodeFromString(serializer, text) }

private val unknownKey = Regex("unknown key '([^']*)'")
private val keyPath = Regex("(?:path|element): (\\$\\S*)")

/** Сообщение kotlinx про незнакомый ключ советует `ignoreUnknownKeys` — агенту нужно другое. */
private inline fun <T> agentReadable(decode: () -> T): T = try {
    decode()
} catch (e: SerializationException) {
    val msg = e.message.orEmpty()
    val key = unknownKey.find(msg)?.groupValues?.get(1) ?: throw e
    val at = keyPath.find(msg)?.groupValues?.get(1)?.let { " в $it" } ?: ""
    throw SerializationException("неизвестное поле '$key'$at — проверьте имя по формату (опечатка или поле другого объекта)")
}
