package train.core

/**
 * Предзаполнение как в Hevy: значения из целевого подхода плана; чего в плане нет — из прошлого
 * факта на том же номере подхода. Берутся только поля, которые есть у типа записи. Диапазон
 * повторов предзаполняется верхней границей.
 */
fun prefill(type: RecordType, target: TargetSet?, last: SetFact?): SetValues = SetValues(
    weight = if (type.usesWeight) target?.weight ?: last?.weight else null,
    reps = if (type.usesReps) target?.repsMax ?: target?.repsMin ?: last?.reps else null,
    extraWeight = if (type.usesExtraWeight) target?.extraWeight ?: last?.extraWeight else null,
    seconds = if (type.usesSeconds) target?.seconds ?: last?.seconds else null,
)

/**
 * Прошлый результат строки: сделанный подход этого упражнения с тем же номером среди `previous` —
 * последних фактов по упражнениям (`GET /exercises/last-sets`).
 */
fun SetRow.last(previous: List<SetFact>): SetFact? =
    previous.firstOrNull { it.exerciseId == item.exerciseId && it.setNo == setNo && it.done && !it.deleted }

/** Значения строки: факт, если он есть, иначе предзаполнение. Правка одного подхода не трогает другие. */
fun SetRow.values(previous: List<SetFact>): SetValues =
    fact?.values ?: prefill(item.recordType, target, last(previous))
