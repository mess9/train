package train.client

import train.core.Measurement
import train.core.Pending
import train.core.SetFact
import train.core.SyncPut
import train.core.SyncRecord
import train.core.Workout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Состояние связи с сервером — то, что телефон показывает в шапке (ADR 0008). */
enum class Link {
    ONLINE,
    OFFLINE,

    /** Cloudflare Access уводит на вход: открыть сайт в браузере и войти. */
    ACCESS_LOGIN,
    KEY_REJECTED,
    SERVER_ERROR,

    /** Ключ другого пользователя: очередь этого телефона ему не отправляется. */
    OTHER_USER,
}

/** Итог отправки одной записи очереди. */
sealed interface SendResult {
    data class Accepted(val server: SyncRecord) : SendResult

    /** [serverOp] — `opId` версии сервера: по нему телефон узнаёт свою потерянную отправку. */
    data class Conflict(val server: SyncRecord, val serverOp: Uuid? = null) : SendResult

    /** Сервер отверг запись (`4xx`, кроме входа): она остаётся в очереди, нужен разбор. */
    data class Rejected(val status: Int, val message: String) : SendResult

    data class Failed(val link: Link) : SendResult
}

/**
 * Связь по ошибке вызова. На wasm редирект с `followRedirects = false` приходит статусом `0`
 * (opaqueredirect), а отсутствие сети — исключением fetch.
 */
fun linkOf(e: Throwable): Link = when (e) {
    is ApiException -> when (e.status) {
        0, in 300..399 -> Link.ACCESS_LOGIN
        401, 403 -> Link.KEY_REJECTED
        // Прочие 4xx — связь есть, сервер отверг сам запрос
        else -> if (e.status >= 500) Link.SERVER_ERROR else Link.ONLINE
    }
    // Отмена корутины — не ошибка сети
    is CancellationException -> throw e
    else -> Link.OFFLINE
}

/** Отправить первую запись очереди. Применять ответ — к свежей сессии, а не к той, что была. */
suspend fun ApiClient.send(p: Pending): SendResult = try {
    val r: PutResult<out SyncRecord> = when (val rec = p.record) {
        is Workout -> putWorkout(SyncPut(rec, p.baseRev, p.opId))
        is SetFact -> putSet(SyncPut(rec, p.baseRev, p.opId))
        is Measurement -> putMeasurement(SyncPut(rec, p.baseRev, p.opId))
    }
    when (r) {
        is PutResult.Accepted -> SendResult.Accepted(r.record)
        is PutResult.Conflict -> SendResult.Conflict(r.current, r.opId)
    }
} catch (e: Throwable) {
    val link = linkOf(e)
    if (link == Link.ONLINE && e is ApiException) SendResult.Rejected(e.status, e.message.orEmpty()) else SendResult.Failed(link)
}
