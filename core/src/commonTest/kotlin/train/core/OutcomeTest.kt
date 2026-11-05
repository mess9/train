package train.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomeTest {
    private val bench = exercise("Жим")
    private val row = exercise("Тяга")
    private val curl = exercise("Сгибания")

    @Test
    fun outcomesOfItems() {
        val full = item(bench, TargetSet(), TargetSet())
        val part = item(row, TargetSet(), TargetSet())
        val none = item(curl, TargetSet())
        val added = item(curl).copy(added = true)
        val removed = item(bench, TargetSet()).copy(removed = true)
        val swapped = item(row, TargetSet()).copy(replacedFrom = bench.id)
        val w = workout(block(full, part, none, added, removed, swapped))
        val facts = listOf(
            w.fact(full, 1), w.fact(full, 2), w.fact(full, 3), // третий сверх плана
            w.fact(part, 1),
            w.fact(added, 1),
        )
        val c = compare(w, facts, listOf(bench, row, curl).associateBy { it.id })
        val items = c.blocks.single().items
        assertEquals(
            listOf(ItemOutcome.DONE, ItemOutcome.PARTIAL, ItemOutcome.SKIPPED, ItemOutcome.ADDED, ItemOutcome.REMOVED, ItemOutcome.REPLACED),
            items.map { it.outcome },
        )
        assertEquals(3, items[0].doneSets)
        assertEquals(2, items[0].plannedSets)
        assertEquals(1, c.outcomes()[ItemOutcome.DONE])
        assertEquals(1, c.outcomes()[ItemOutcome.ADDED])
    }
}
