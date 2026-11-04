package train.core

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Что лежит в телефоне под ключом `session` (ADR 0007): активная тренировка с подходами и очередь
 * отправки — один документ, пишется целиком на каждое действие. Очередь привязана к [userId]:
 * с ключом другого пользователя из неё ничего не отправляется (ADR 0008).
 *
 * Действия ниже — чистые функции: новое состояние записи сразу встаёт в очередь.
 */
@Serializable
data class Session(
    val userId: Uuid,
    val workout: Workout? = null,
    val sets: List<SetFact> = emptyList(),
    /** Упражнения тренировки — чтобы показывать её без сети. */
    val exercises: List<Exercise> = emptyList(),
    /** Последние факты этих упражнений: прошлый результат и предзаполнение. */
    val previous: List<SetFact> = emptyList(),
    val outbox: Outbox = Outbox(),
) {
    /** Тренировка идёт: её можно менять. Завершённая остаётся здесь до экрана «Готово». */
    val active get() = workout?.takeIf { it.active }

    fun exercise(id: Uuid) = exercises.firstOrNull { it.id == id }

    fun passes() = workout?.layout?.passes(sets).orEmpty()

    fun block(blockId: Uuid) = workout?.layout?.blocks?.firstOrNull { it.id == blockId }
}

/** Начать тренировку: снимок целей плана (или своя раскладка) встаёт в очередь первым. */
fun Session.start(
    plan: Plan?,
    layout: WorkoutLayout,
    exercises: List<Exercise>,
    previous: List<SetFact>,
    now: Instant,
): Session {
    val w = Workout(newId(), plan?.id, plan?.version, startedAt = now, layout = layout)
    return copy(workout = w, sets = emptyList(), exercises = exercises, previous = previous).record(w)
}

/**
 * Тап по оценке. Подход не отмечен — отмечается с текущими значениями и временем; отмечен этой же
 * оценкой — отметка снимается; другой — меняется только оценка.
 */
fun Session.rate(blockId: Uuid, itemId: Uuid, setNo: Int, rating: Rating, now: Instant): Session {
    val f = factFor(blockId, itemId, setNo) ?: return this
    return record(
        when {
            f.done && f.rating == rating -> f.copy(done = false, rating = null, doneAt = null)
            f.done -> f.copy(rating = rating)
            else -> f.copy(done = true, rating = rating, doneAt = now)
        },
    )
}

/** Правка значений подхода — до и после отметки. Другие подходы не меняются. */
fun Session.edit(blockId: Uuid, itemId: Uuid, setNo: Int, values: SetValues): Session {
    val f = factFor(blockId, itemId, setNo) ?: return this
    if (f.values == values) return this
    return record(f.copy(weight = values.weight, reps = values.reps, extraWeight = values.extraWeight, seconds = values.seconds))
}

/** «+ подход»: сверх плана, значения — как у последней строки. */
fun Session.addSet(blockId: Uuid, itemId: Uuid): Session {
    val w = active ?: return this
    val (block, item) = item(blockId, itemId) ?: return this
    val rows = BlockPasses(block, sets).rows(item)
    val v = rows.lastOrNull()?.values(previous) ?: prefill(item.recordType, null, null)
    val setNo = maxOf(item.sets.size, sets.filter { it.itemId == item.id }.maxOfOrNull { it.setNo } ?: 0) + 1
    return record(newFact(w, block, item, setNo, null, v))
}

/** Убрать последний невыполненный подход. Плановый остаётся в `compare` пропущенным. */
fun Session.removeSet(blockId: Uuid, itemId: Uuid): Session {
    val (block, item) = item(blockId, itemId) ?: return this
    val row = BlockPasses(block, sets).rows(item).lastOrNull { !it.done } ?: return this
    val f = factFor(blockId, itemId, row.setNo) ?: return this
    return record(f.copy(deleted = true))
}

/** Удалить подход `setNo`, в том числе выполненный: в `compare` плановый станет пропущенным. */
fun Session.removeSet(blockId: Uuid, itemId: Uuid, setNo: Int): Session {
    val f = factFor(blockId, itemId, setNo) ?: return this
    return record(f.copy(deleted = true))
}

/** Сменить тип записи на месте: новые подходы — с ним, сделанные сохраняют свой. */
fun Session.setRecordType(blockId: Uuid, itemId: Uuid, type: RecordType): Session =
    changeItem(blockId, itemId, { it.copy(recordType = type) }) { it.copy(recordType = type) }

/** Замена на ходу: сделанные подходы остаются со старым упражнением, цели — те же. */
fun Session.replace(blockId: Uuid, itemId: Uuid, ex: Exercise, previous: List<SetFact> = emptyList()): Session =
    withExercise(ex, previous).changeItem(
        blockId,
        itemId,
        { it.copy(exerciseId = ex.id, replacedFrom = it.replacedFrom ?: it.exerciseId, recordType = ex.recordType, perSide = ex.perSide) },
    ) { it.copy(exerciseId = ex.id, recordType = ex.recordType, perSide = ex.perSide) }

/** «+ упражнение в блок»: подходов — по числу проходов блока, значения — из прошлого раза. */
fun Session.addItem(blockId: Uuid, ex: Exercise, previous: List<SetFact> = emptyList()): Session {
    val w = active ?: return this
    val block = block(blockId) ?: return this
    val item = LayoutItem(newId(), ex.id, ex.recordType, ex.perSide, sets = emptyList(), added = true)
    val count = BlockPasses(block, sets).passCount.coerceAtLeast(1)
    val withItem = withExercise(ex, previous).layout(blockId) { it.copy(items = it.items + item) }
    val newBlock = withItem.block(blockId)!!
    return (1..count).fold(withItem) { s, n ->
        val last = s.previous.firstOrNull { it.exerciseId == ex.id && it.setNo == n && it.done && !it.deleted }
        s.record(newFact(w, newBlock, item, n, null, prefill(item.recordType, null, last)))
    }
}

/** Сдвинуть упражнение в блоке: `-1` — выше, `1` — ниже. Убранные пропускаются. */
fun Session.move(blockId: Uuid, itemId: Uuid, direction: Int): Session = layout(blockId) { b ->
    val visible = b.items.withIndex().filterNot { it.value.removed }
    val pos = visible.indexOfFirst { it.value.id == itemId }
    val other = visible.getOrNull(pos + direction)
    if (pos < 0 || other == null) b
    else b.copy(items = b.items.toMutableList().also { it[visible[pos].index] = other.value; it[other.index] = visible[pos].value })
}

/** Убрать из тренировки. Сделанные подходы остаются в факте. */
fun Session.removeItem(blockId: Uuid, itemId: Uuid): Session = changeItem(blockId, itemId, { it.copy(removed = true) }) { it }

fun Session.finish(at: Instant): Session {
    val w = active ?: return this
    return record(w.copy(finishedAt = at))
}

fun Session.comment(text: String): Session {
    val w = workout ?: return this
    return record(w.copy(comment = text.ifBlank { null }))
}

/** Экран завершения закрыт: тренировка уходит из телефона, очередь остаётся. */
fun Session.close(): Session =
    if (active != null) this else copy(workout = null, sets = emptyList(), exercises = emptyList(), previous = emptyList())

fun Session.measure(m: Measurement): Session = copy(outbox = outbox.put(m))

/**
 * Забытая тренировка — без отметок дольше [idle]. Закрыть её предлагается временем последней
 * отметки + 5 минут; `null` — не забыта.
 */
fun Session.forgottenEnd(now: Instant, idle: Duration = 4.hours): Instant? {
    val w = active ?: return null
    val last = sets.filter { it.done && !it.deleted }.mapNotNull { it.doneAt }.maxOrNull() ?: w.startedAt
    return if (now - last > idle) last + 5.minutes else null
}

// Ответы сервера (ADR 0007)

/** Сервер принял отправку: версия записи теперь его. */
fun Session.accepted(sent: Pending, server: SyncRecord): Session =
    copy(outbox = outbox.accepted(sent.opId, server)).withRev(server)

/**
 * `409`: у сервера другая версия. Во время тренировки побеждает телефон — запись уйдёт снова
 * поверх версии сервера; в завершённой и в замерах — сервер.
 *
 * Версия сервера с [serverOp] из прежних состояний этой записи — это отправка телефона, ответ на
 * которую потерялся. Тогда тоже побеждает телефон, иначе финиш или правка после неё пропали бы.
 */
fun Session.conflict(server: SyncRecord, serverOp: Uuid? = null): Session {
    val phoneWins = outbox.own(server, serverOp) || when (server) {
        is Workout -> active?.id == server.id
        is SetFact -> active?.id == server.workoutId
        is Measurement -> false
    }
    val s = copy(outbox = outbox.conflict(server, phoneWins))
    return if (phoneWins) s.withRev(server) else s.replaceWith(server)
}

// Внутреннее

private fun Session.record(w: Workout) = copy(workout = w, outbox = outbox.put(w))

private fun Session.record(f: SetFact) =
    copy(sets = sets.filterNot { it.id == f.id } + f, outbox = outbox.put(f))

private fun Session.withRev(server: SyncRecord) = when (server) {
    is Workout -> if (workout?.id == server.id) copy(workout = workout.copy(rev = server.rev)) else this
    is SetFact -> copy(sets = sets.map { if (it.id == server.id) it.copy(rev = server.rev) else it })
    is Measurement -> this
}

private fun Session.replaceWith(server: SyncRecord) = when (server) {
    is Workout -> if (workout?.id == server.id) copy(workout = server) else this
    is SetFact -> copy(sets = sets.map { if (it.id == server.id) server else it })
    is Measurement -> this
}

private fun Session.withExercise(ex: Exercise, previous: List<SetFact>) = copy(
    exercises = exercises.filterNot { it.id == ex.id } + ex,
    previous = this.previous + previous.filter { p -> this.previous.none { it.id == p.id } },
)

private fun Session.item(blockId: Uuid, itemId: Uuid): Pair<LayoutBlock, LayoutItem>? {
    val block = block(blockId) ?: return null
    return block to (block.items.firstOrNull { it.id == itemId } ?: return null)
}

/** Факт строки: уже записанный или новый, неотмеченный, со значениями предзаполнения. */
private fun Session.factFor(blockId: Uuid, itemId: Uuid, setNo: Int): SetFact? {
    val w = active ?: return null
    val (block, item) = item(blockId, itemId) ?: return null
    sets.firstOrNull { it.itemId == itemId && it.setNo == setNo && !it.deleted }?.let { return it }
    val row = BlockPasses(block, sets).rows(item).firstOrNull { it.setNo == setNo } ?: return null
    return newFact(w, block, item, setNo, row.target?.id, row.values(previous))
}

private fun newFact(w: Workout, block: LayoutBlock, item: LayoutItem, setNo: Int, targetId: Uuid?, v: SetValues) = SetFact(
    id = newId(),
    workoutId = w.id,
    blockId = block.id,
    itemId = item.id,
    setNo = setNo,
    targetId = targetId,
    exerciseId = item.exerciseId,
    recordType = item.recordType,
    perSide = item.perSide,
    weight = v.weight,
    reps = v.reps,
    extraWeight = v.extraWeight,
    seconds = v.seconds,
)

private fun Session.layout(blockId: Uuid, change: (LayoutBlock) -> LayoutBlock): Session {
    val w = active ?: return this
    val blocks = w.layout.blocks.map { if (it.id == blockId) change(it) else it }
    if (blocks == w.layout.blocks) return this
    return record(w.copy(layout = WorkoutLayout(blocks)))
}

/** Правка упражнения в раскладке и его невыполненных подходов. */
private fun Session.changeItem(
    blockId: Uuid,
    itemId: Uuid,
    item: (LayoutItem) -> LayoutItem,
    openFact: (SetFact) -> SetFact,
): Session {
    val s = layout(blockId) { b -> b.copy(items = b.items.map { if (it.id == itemId) item(it) else it }) }
    return s.sets.filter { it.itemId == itemId && !it.done && !it.deleted }
        .fold(s) { acc, f -> openFact(f).let { if (it == f) acc else acc.record(it) } }
}
