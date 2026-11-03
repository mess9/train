package train.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlinx.datetime.LocalDate
import kotlinx.serialization.serializer
import train.core.API_KEY_HEADER
import train.core.API_PREFIX
import train.core.ApiError
import train.core.ApiKey
import train.core.Comparison
import train.core.Exercise
import train.core.ExerciseCreate
import train.core.ExercisePatch
import train.core.KeyCreate
import train.core.KeyCreated
import train.core.Me
import train.core.Measurement
import train.core.OP_ID_HEADER
import train.core.Plan
import train.core.PlanPut
import train.core.Profile
import train.core.Purged
import train.core.ServerInfo
import train.core.SetFact
import train.core.SourceExercise
import train.core.SyncPut
import train.core.Trash
import train.core.TrainJson
import train.core.Workout
import train.core.WorkoutDetail
import kotlin.uuid.Uuid

/** Ответ не 2xx. [message] — `error` из тела, если сервер его прислал. */
class ApiException(val status: Int, message: String) : Exception(message)

/** Итог записи, которую сервер может отклонить по версии (ADR 0007). */
sealed interface PutResult<T> {
    data class Accepted<T>(val record: T) : PutResult<T>

    /** `409`: у сервера другая версия — вот она; [opId] — её операция, если сервер назвал. */
    data class Conflict<T>(val current: T, val opId: Uuid? = null) : PutResult<T>
}

/**
 * Клиент API для CLI и приложения. Движок HTTP выбирает вызывающий: Java в CLI, fetch в браузере.
 * [extraHeaders] — например, сервисный токен Cloudflare Access у CLI.
 */
class ApiClient(
    private val http: HttpClient,
    baseUrl: String,
    var key: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
) {
    private val root = baseUrl.trimEnd('/')
    private val api = root + API_PREFIX

    suspend fun info(): ServerInfo = get("/info")

    suspend fun me(): Me = get("/me")

    // Ключи

    suspend fun createKey(label: String?): KeyCreated = send(HttpMethod.Post, "/keys", KeyCreate(label))

    suspend fun keys(): List<ApiKey> = get("/keys")

    suspend fun revokeKey(id: Uuid) = call(HttpMethod.Delete, "/keys/$id")

    // Справочник и источник

    suspend fun exercises(q: String? = null): List<Exercise> = get("/exercises" + query("q" to q))

    suspend fun exercise(id: Uuid): Exercise = get("/exercises/$id")

    suspend fun createExercise(body: ExerciseCreate): Exercise = send(HttpMethod.Post, "/exercises", body)

    suspend fun patchExercise(id: Uuid, body: ExercisePatch): Exercise = send(HttpMethod.Patch, "/exercises/$id", body)

    /** Подходы последней тренировки с каждым упражнением; [exclude] — текущая тренировка. */
    suspend fun lastSets(ids: Collection<Uuid>, exclude: Uuid? = null): List<SetFact> =
        get("/exercises/last-sets" + query("ids" to ids.joinToString(","), "exclude" to exclude?.toString()))

    suspend fun searchSource(q: String? = null, limit: Int? = null): List<SourceExercise> =
        get("/source/exercises" + query("q" to q, "limit" to limit?.toString()))

    suspend fun sourceExercise(id: String): SourceExercise = get("/source/exercises/${id.encodeURLParameter()}")

    // Планы

    suspend fun plans(from: LocalDate? = null, to: LocalDate? = null): List<Plan> =
        get("/plans" + query("from" to from?.toString(), "to" to to?.toString()))

    suspend fun plan(id: Uuid): Plan = get("/plans/$id")

    /** Начатый план сервер не меняет — [PutResult.Conflict] с текущим состоянием. */
    suspend fun putPlan(id: Uuid, body: PlanPut): PutResult<Plan> = put("/plans/$id", body)

    suspend fun deletePlan(id: Uuid) = call(HttpMethod.Delete, "/plans/$id")

    // Тренировки

    suspend fun workouts(from: LocalDate? = null, to: LocalDate? = null): List<Workout> =
        get("/workouts" + query("from" to from?.toString(), "to" to to?.toString()))

    suspend fun workout(id: Uuid): WorkoutDetail = get("/workouts/$id")

    suspend fun putWorkout(body: SyncPut<Workout>): PutResult<Workout> = put("/workouts/${body.data.id}", body)

    suspend fun putSet(body: SyncPut<SetFact>): PutResult<SetFact> =
        put("/workouts/${body.data.workoutId}/sets/${body.data.id}", body)

    suspend fun compare(workoutId: Uuid): Comparison = get("/workouts/$workoutId/compare")

    // Замеры, профиль, корзина

    suspend fun measurements(type: String? = null, from: LocalDate? = null, to: LocalDate? = null): List<Measurement> =
        get("/measurements" + query("type" to type, "from" to from?.toString(), "to" to to?.toString()))

    suspend fun measurement(id: Uuid): Measurement = get("/measurements/$id")

    suspend fun putMeasurement(body: SyncPut<Measurement>): PutResult<Measurement> = put("/measurements/${body.data.id}", body)

    suspend fun profile(): Profile = get("/profile")

    suspend fun putProfile(body: Profile): Profile = send(HttpMethod.Put, "/profile", body)

    suspend fun trash(): Trash = get("/trash")

    suspend fun purgeTrash(): Purged = send(HttpMethod.Post, "/trash/purge", null as String?)

    /** Картинка или гифка датасета по имени файла, например `0001-2gPfomN.jpg`. */
    suspend fun media(name: String): ByteArray {
        val r = http.request("$root/media/${name.encodeURLParameter()}") { auth() }
        check(r)
        return r.bodyAsBytes()
    }

    // Общее

    private suspend inline fun <reified T> get(path: String): T = decode(call(HttpMethod.Get, path))

    private suspend inline fun <reified B, reified T> send(method: HttpMethod, path: String, body: B): T =
        decode(call(method, path, encode(body)))

    /** Запись с возможным `409`: тело конфликта — текущая версия у сервера. */
    private suspend inline fun <reified B, reified T> put(path: String, body: B): PutResult<T> {
        val r = request(HttpMethod.Put, path, encode(body))
        if (r.status.value == 409) {
            val op = r.headers[OP_ID_HEADER]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            return PutResult.Conflict(TrainJson.decodeFromString(serializer<T>(), r.bodyAsText()), op)
        }
        check(r)
        return PutResult.Accepted(TrainJson.decodeFromString(serializer<T>(), r.bodyAsText()))
    }

    private inline fun <reified B> encode(body: B): String? =
        body?.let { TrainJson.encodeToString(serializer<B>(), it) }

    private inline fun <reified T> decode(text: String): T = TrainJson.decodeFromString(serializer<T>(), text)

    private suspend fun call(method: HttpMethod, path: String, body: String? = null): String {
        val r = request(method, path, body)
        check(r)
        return r.bodyAsText()
    }

    private suspend fun request(method: HttpMethod, path: String, body: String?): HttpResponse =
        http.request(api + path) {
            this.method = method
            auth()
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
        }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        key?.let { header(API_KEY_HEADER, it) }
        extraHeaders.forEach { (k, v) -> header(k, v) }
    }

    private suspend fun check(r: HttpResponse) {
        val status = r.status.value
        if (status in 200..299) return
        if (status == 0 || status in 300..399) {
            // Cloudflare Access уводит на страницу входа: нужен сервисный токен или вход в браузере.
            // В браузере без перехода по редиректу fetch отдаёт статус 0 (opaqueredirect)
            throw ApiException(status, "редирект на ${r.headers[HttpHeaders.Location] ?: "?"} — нужен вход в Cloudflare Access")
        }
        val text = r.bodyAsText()
        val message = runCatching { TrainJson.decodeFromString(ApiError.serializer(), text).error }.getOrNull()
            ?: text.take(300).ifBlank { r.status.description }
        throw ApiException(status, message)
    }

    private fun query(vararg params: Pair<String, String?>): String =
        params.filter { !it.second.isNullOrEmpty() }
            .joinToString("&") { (k, v) -> "$k=${v!!.encodeURLParameter()}" }
            .let { if (it.isEmpty()) "" else "?$it" }

}
