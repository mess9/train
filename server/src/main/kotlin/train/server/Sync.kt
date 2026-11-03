package train.server

import jakarta.ws.rs.core.Response
import kotlinx.serialization.KSerializer
import org.jboss.resteasy.reactive.RestResponse
import train.core.OP_ID_HEADER
import train.core.SyncPut
import train.core.SyncRecord

/**
 * Протокол записи (ADR 0007), общий для тренировок, подходов и замеров. Вызывать в транзакции.
 * [load] читает строку `for update` и проверяет владельца (чужая — `404`).
 *
 * - записи нет — создаётся с `rev = 1`;
 * - тот же `opId`, что у последней записи, — `200` с текущим состоянием, без изменений;
 * - `baseRev` не совпал — `409` с текущим состоянием и его `opId` в заголовке [OP_ID_HEADER]:
 *   по нему телефон узнаёт свою же отправку, ответ на которую потерялся;
 * - иначе прежняя версия уходит в `revision_log`, новая пишется с `rev + 1`.
 *
 * [write] — `insert … on conflict (id) do update … where rev = новая - 1 and user_id = владелец`,
 * возвращает число строк. Две первые записи одного ID разом обе не находят строку; вторая
 * получает `0` — тогда строка перечитывается и запись идёт по ней, как по обычной.
 *
 * Ответ — запись, как её прочитал [read] после записи: время в базе — с точностью до микросекунд,
 * и повтор с тем же `opId` должен вернуть то же самое.
 */
fun <T : SyncRecord> sync(
    entity: String,
    serializer: KSerializer<T>,
    put: SyncPut<T>,
    load: () -> Stored<T>?,
    log: Db,
    read: () -> T,
    write: (record: T, rev: Long) -> Int,
): RestResponse<T> {
    val current = load()
    if (current != null) {
        if (current.opId == put.opId) return RestResponse.ok(current.record)
        if (current.record.rev != put.baseRev) {
            return RestResponse.ResponseBuilder.create(Response.Status.CONFLICT, current.record)
                .header(OP_ID_HEADER, current.opId.toString())
                .build()
        }
        log.update(
            "insert into revision_log (entity, entity_id, rev, data) values (?, ?, ?, cast(? as jsonb))",
            entity, current.record.id, current.record.rev, Json.of(serializer, current.record),
        )
    }
    val rev = (current?.record?.rev ?: 0) + 1
    if (write(put.data, rev) == 0) {
        // Строка под замком меняется только здесь — ноль бывает лишь при гонке первых записей
        check(current == null) { "$entity ${put.data.id}: строка под замком не обновилась" }
        return sync(entity, serializer, put, { load() ?: error("$entity ${put.data.id}: нет строки после конфликта") }, log, read, write)
    }
    return RestResponse.ok(read())
}
