package train.core

/** Строка подхода на экране: цель и/или факт. */
data class SetRow(val item: LayoutItem, val setNo: Int, val target: TargetSet?, val fact: SetFact?) {
    val done get() = fact?.done == true
}

enum class RestKind { AFTER_EXERCISE, AFTER_PASS }

data class Rest(val kind: RestKind, val seconds: Int?)

/**
 * Проходы блока (ADR 0004). Проход k — подходы с номером k у всех упражнений блока, у которых
 * такой есть: круг может быть неровным. Решает состояние подходов, а не позиция в списке.
 *
 * Строки упражнения — цели раскладки плюс факты сверх них. Факт с `deleted` прячет строку,
 * в том числе плановую: такой подход в `compare` пропущен.
 */
class BlockPasses(val block: LayoutBlock, facts: List<SetFact>) {
    private val factsByItem = facts.filter { it.blockId == block.id }.groupBy { it.itemId }

    val items = block.items.filterNot { it.removed }

    fun rows(item: LayoutItem): List<SetRow> {
        val bySetNo = factsByItem[item.id].orEmpty().associateBy { it.setNo }
        val count = maxOf(item.sets.size, bySetNo.keys.maxOrNull() ?: 0)
        return (1..count).mapNotNull { n ->
            val fact = bySetNo[n]
            val target = item.sets.getOrNull(n - 1)
            if (fact?.deleted == true || (fact == null && target == null)) null
            else SetRow(item, n, target, fact)
        }
    }

    private val allRows = items.flatMap(::rows)

    /** N в «проход k из N»: самый большой номер подхода. */
    val passCount = allRows.maxOfOrNull { it.setNo } ?: 0

    /** k в «проход k из N»: наименьший номер с невыполненным подходом; `null` — блок пройден. */
    val currentPass = allRows.filterNot { it.done }.minOfOrNull { it.setNo }

    val finished get() = currentPass == null

    /** Строка в свёрнутой карточке: первый невыполненный подход упражнения. */
    fun next(item: LayoutItem): SetRow? = rows(item).firstOrNull { !it.done }

    fun passClosed(setNo: Int) = allRows.filter { it.setNo == setNo }.all { it.done }

    /**
     * Отдых после отметки подхода `setNo` у `item`; считать по состоянию, где отметка уже есть.
     * Проход закрыт — отдых после прохода (если в блоке не задан — отдых упражнения), иначе —
     * после упражнения.
     */
    fun restAfter(item: LayoutItem, setNo: Int): Rest =
        if (passClosed(setNo)) Rest(RestKind.AFTER_PASS, block.restAfterPassSec ?: item.restSec)
        else Rest(RestKind.AFTER_EXERCISE, item.restSec)
}

fun WorkoutLayout.passes(facts: List<SetFact>) = blocks.map { BlockPasses(it, facts) }
