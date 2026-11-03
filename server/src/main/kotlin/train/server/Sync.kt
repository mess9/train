package train.server

import jakarta.ws.rs.core.Response
import kotlinx.serialization.KSerializer
import org.jboss.resteasy.reactive.RestResponse
import train.core.SyncPut
import train.core.SyncRecord

/**
 * Протокол записи (ADR 0007), общий для тренировок, подходов и замеров. Вызывать в транзакции,
 * `current` — строка, прочитанная `for update` и уже проверенная на владельца.
 *
 * - записи нет — создаётся с `rev = 1`;
 * - тот же `opId`, что у последней записи, — `200` с текущим состоянием, без изменений;
 * - `baseRev` не совпал — `409` с текущим состоянием;
 * - иначе прежняя версия уходит в `revision_log`, новая пишется с `rev + 1`.
 *
 * Ответ — запись, как её прочитал [read] после записи: время в базе — с точностью до микросекунд,
 * и повтор с тем же `opId` должен вернуть то же самое.
 */
fun <T : SyncRecord> sync(
    entity: String,
    serializer: KSerializer<T>,
    put: SyncPut<T>,
    current: Stored<T>?,
    log: Db,
    read: () -> T,
    write: (record: T, rev: Long) -> Unit,
): RestResponse<T> {
    if (current != null) {
        if (current.opId == put.opId) return RestResponse.ok(current.record)
        if (current.record.rev != put.baseRev) return RestResponse.status(Response.Status.CONFLICT, current.record)
        log.update(
            "insert into revision_log (entity, entity_id, rev, data) values (?, ?, ?, cast(? as jsonb))",
            entity, current.record.id, current.record.rev, Json.of(serializer, current.record),
        )
    }
    val rev = (current?.record?.rev ?: 0) + 1
    write(put.data, rev)
    return RestResponse.ok(read())
}
