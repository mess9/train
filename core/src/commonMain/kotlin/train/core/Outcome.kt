package train.core

/** Итог упражнения в тренировке — для экрана завершения и истории. */
enum class ItemOutcome {
    /** Все плановые подходы сделаны. */
    DONE,
    /** Сделана часть плановых. */
    PARTIAL,
    /** Ни одного подхода. */
    SKIPPED,
    /** Не было в плане, добавлено на ходу. */
    ADDED,
    /** Упражнение заменено на ходу. */
    REPLACED,
    /** Убрано из тренировки, подходов нет. */
    REMOVED,
}

/** Сделанные подходы: плановые и сверх плана. */
val ItemComparison.doneSets get() = sets.count { it.fact != null }

/** Плановые подходы. */
val ItemComparison.plannedSets get() = sets.count { it.target != null }

val ItemComparison.outcome: ItemOutcome
    get() {
        val done = doneSets
        return when {
            added -> ItemOutcome.ADDED
            replacedFrom != null -> ItemOutcome.REPLACED
            removed && done == 0 -> ItemOutcome.REMOVED
            done == 0 -> ItemOutcome.SKIPPED
            sets.any { it.status == SetStatus.SKIPPED } -> ItemOutcome.PARTIAL
            else -> ItemOutcome.DONE
        }
    }

/** Сколько упражнений с каким итогом: «сделано 5, пропущено 1, добавлено 1». */
fun Comparison.outcomes(): Map<ItemOutcome, Int> =
    blocks.flatMap { it.items }.groupingBy { it.outcome }.eachCount()
