package train.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val API_PREFIX = "/api"

/** Ключ API (ADR 0008). */
const val API_KEY_HEADER = "X-Api-Key"

/** В ответе `409` — `opId` текущей версии записи (ADR 0007). */
const val OP_ID_HEADER = "X-Op-Id"

/**
 * Устройство приложения: постоянный ID установки. Активную тренировку ведёт одно устройство —
 * то, что её начало или забрало (`POST /api/workouts/{id}/take`); запись с другого — `423`.
 * Без заголовка (CLI, агент) замок не проверяется.
 */
const val DEVICE_HEADER = "X-Device"

/** Вид устройства для подписи «идёт в приложении» / «в браузере»: [DEVICE_APK] или [DEVICE_WEB]. */
const val DEVICE_KIND_HEADER = "X-Device-Kind"
const val DEVICE_APK = "apk"
const val DEVICE_WEB = "web"

/** Тренировку ведёт другое устройство: её забрали, запись с этого отвергнута. */
const val STATUS_LOCKED = 423

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
 * [version] — semver с коммитом сборки, `0.3.33+5cdae85` (последнее число — номер сборки
 * Jenkins); вне сборки образа — `0.3.0+dev`.
 * [commit] — короткий хеш коммита, [build] — номер сборки Jenkins, если есть (ADR 0009).
 * [app] — хеш сборки PWA, которую сервер отдаёт сейчас: приложение сравнивает его со своим и
 * видит, что пора перезапуститься. [dataset] — подключён ли датасет упражнений (docs/dataset.md).
 */
@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val commit: String? = null,
    val build: String? = null,
    val app: String? = null,
    val dataset: DatasetInfo? = null,
)

/**
 * [exercises] — записей в `exercises.json` датасета: `0` — файла нет, `exercise search` пустой.
 * [media] — папка картинок и гифок есть; нет — у упражнений из датасета не будет картинок.
 */
@Serializable
data class DatasetInfo(val exercises: Int, val media: Boolean)

/**
 * Версия сборки Jenkins: последнее число semver — номер сборки, `0.3.0` и `33` → `0.3.33`. Без
 * номера (локально, тесты) или с нечисловым — версия как есть (ADR 0009).
 */
fun buildVersion(version: String = TRAIN_VERSION, build: String?): String {
    val n = build?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return version
    val parts = version.split('.')
    return if (parts.size == 3) "${parts[0]}.${parts[1]}.${n.trimStart('0').ifEmpty { "0" }}" else version
}

/** `0.2.0+5cdae85`: semver и коммит как метаданные сборки. Без коммита — `0.2.0+dev`. */
fun fullVersion(version: String = TRAIN_VERSION, commit: String?): String = "$version+${commit?.takeIf { it.isNotBlank() } ?: "dev"}"
