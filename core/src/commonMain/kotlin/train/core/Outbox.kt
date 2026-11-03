package train.core

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Неотправленное состояние записи. `record.rev` — версия сервера, от которой оно сделано.
 * [replaced] — `opId` прежних состояний той же записи с той же базовой версией: какое-то из них
 * могло дойти до сервера, а ответ потеряться.
 */
@Serializable
data class Pending(val record: SyncRecord, val opId: Uuid, val replaced: List<Uuid> = emptyList()) {
    val baseRev get() = record.rev
}

/**
 * Очередь отправки (ADR 0007): не журнал операций, а последнее состояние каждой записи.
 * «Отметил → снял → отметил» уходит одной отправкой. Порядок отправки — порядок первого
 * появления записи: тренировка раньше своих подходов.
 */
@Serializable
data class Outbox(val pending: List<Pending> = emptyList()) {
    val size get() = pending.size

    /** Что отправлять следующим. Повтор без новых правок идёт с тем же `opId`. */
    fun next(): Pending? = pending.firstOrNull()

    /**
     * Новое состояние записи. Уже ждёт отправки — заменяется на месте с прежней базовой версией
     * и новым `opId`: содержимое другое, значит и операция другая.
     */
    fun put(record: SyncRecord): Outbox {
        val i = pending.indexOfFirst { it.record.id == record.id }
        if (i < 0) return Outbox(pending + Pending(record, newId()))
        val p = pending[i]
        return replace(i, Pending(record.withRev(p.baseRev), newId(), p.replaced + p.opId))
    }

    /**
     * Сервер принял `opId` и вернул запись. Если с тех пор её не правили — она уходит из очереди;
     * правили — остаётся, но уже поверх новой версии сервера.
     */
    fun accepted(opId: Uuid, server: SyncRecord): Outbox {
        val i = pending.indexOfFirst { it.record.id == server.id }
        if (i < 0) return this
        val p = pending[i]
        return if (p.opId == opId) remove(i) else replace(i, Pending(p.record.withRev(server.rev), p.opId))
    }

    /** Версия сервера с `opId` [serverOp] — прежнее состояние этой же записи из очереди. */
    fun own(server: SyncRecord, serverOp: Uuid?): Boolean =
        serverOp != null && pending.any { it.record.id == server.id && serverOp in it.replaced }

    /**
     * Сервер ответил `409` с текущим состоянием. Тренировка активна — побеждает телефон: та же
     * запись уходит снова поверх версии сервера. Завершена — побеждает сервер: запись снимается с
     * очереди, телефон берёт состояние сервера.
     */
    fun conflict(server: SyncRecord, phoneWins: Boolean): Outbox {
        val i = pending.indexOfFirst { it.record.id == server.id }
        if (i < 0) return this
        val p = pending[i]
        return if (phoneWins) replace(i, Pending(p.record.withRev(server.rev), newId())) else remove(i)
    }

    private fun replace(i: Int, p: Pending) = Outbox(pending.toMutableList().also { it[i] = p })
    private fun remove(i: Int) = Outbox(pending.toMutableList().also { it.removeAt(i) })
}
