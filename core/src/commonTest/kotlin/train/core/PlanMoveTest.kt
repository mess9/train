package train.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PlanMoveTest {
    private fun item(note: String) = BlockItem(exerciseId = newId(), sets = listOf(TargetSet(repsMax = 10)), note = note)

    private val warm = Block(role = BlockRole.WARMUP, items = listOf(item("w1"), item("w2")))
    private val circle = Block(items = listOf(item("a"), item("b"), item("c"), item("d")), restAfterPassSec = 120)
    private val body = PlanBody(listOf(warm, circle), note = "заметка")

    private fun PlanBody.notes(i: Int) = blocks[i].items.map { it.note }

    @Test
    fun movesDownAndUpShiftingTheRest() {
        assertEquals(listOf("b", "c", "a", "d"), body.moveItem(circle.id, 0, 2).notes(1))
        assertEquals(listOf("d", "a", "b", "c"), body.moveItem(circle.id, 3, 0).notes(1))
    }

    @Test
    fun keepsTargetsIdsAndOtherBlocks() {
        val moved = body.moveItem(circle.id, 1, 3)
        assertEquals(circle.items.toSet(), moved.blocks[1].items.toSet())
        assertEquals(warm, moved.blocks[0])
        assertEquals(circle.restAfterPassSec, moved.blocks[1].restAfterPassSec)
        assertEquals("заметка", moved.note)
    }

    @Test
    fun badIndexOrBlockChangesNothing() {
        assertEquals(body, body.moveItem(circle.id, 0, 4))
        assertEquals(body, body.moveItem(circle.id, 2, 2))
        assertEquals(body, body.moveItem(newId(), 0, 1))
    }
}
