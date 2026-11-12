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
 * [version] — semver с коммитом сборки, `0.2.0+5cdae85`; вне сборки образа — `0.2.0+dev`.
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

/** `0.2.0+5cdae85`: semver и коммит как метаданные сборки. Без коммита — `0.2.0+dev`. */
fun fullVersion(version: String = TRAIN_VERSION, commit: String?): String = "$version+${commit?.takeIf { it.isNotBlank() } ?: "dev"}"
