package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassesTest {
    private val squat = exercise("Присед")
    private val row = exercise("Тяга")
    // Неровный круг: у приседа три подхода, у тяги два
    private val a = item(squat, TargetSet(), TargetSet(), TargetSet(), restSec = 30)
    private val b = item(row, TargetSet(), TargetSet(), restSec = 45)
    private val circle = block(a, b, restAfterPassSec = 120)
    private val w = workout(circle)

    private fun passes(vararg facts: SetFact) = BlockPasses(circle, facts.toList())

    @Test
    fun unevenCircle() {
        val p = passes()
        assertEquals(3, p.passCount)
        assertEquals(1, p.currentPass)
        assertEquals(listOf(1, 2, 3), p.rows(a).map { it.setNo })
        assertEquals(listOf(1, 2), p.rows(b).map { it.setNo })
    }

    @Test
    fun currentPassIsLowestWithUndone() {
        // Второй проход приседа сделан раньше первого прохода тяги — проход всё равно первый
        val p = passes(w.fact(a, 1), w.fact(a, 2))
        assertEquals(1, p.currentPass)
        assertEquals(1, p.next(b)?.setNo)
        assertEquals(3, p.next(a)?.setNo)
    }

    @Test
    fun restDependsOnPassState() {
        // Присед 1 — проход не закрыт, отдых упражнения
        assertEquals(Rest(RestKind.AFTER_EXERCISE, 30), passes(w.fact(a, 1)).restAfter(a, 1))
        // Тяга 1 закрыла проход
        assertEquals(Rest(RestKind.AFTER_PASS, 120), passes(w.fact(a, 1), w.fact(b, 1)).restAfter(b, 1))
        // Третий проход — только присед: его отметка и закрывает проход
        val all = passes(w.fact(a, 1), w.fact(b, 1), w.fact(a, 2), w.fact(b, 2), w.fact(a, 3))
        assertEquals(Rest(RestKind.AFTER_PASS, 120), all.restAfter(a, 3))
        assertTrue(all.finished)
        assertNull(all.currentPass)
    }

    @Test
    fun singleExerciseBlockFallsBackToExerciseRest() {
        val single = item(squat, TargetSet(), TargetSet(), restSec = 90)
        val blk = block(single, restAfterPassSec = null)
        val wk = workout(blk)
        assertEquals(Rest(RestKind.AFTER_PASS, 90), BlockPasses(blk, listOf(wk.fact(single, 1))).restAfter(single, 1))
    }

    @Test
    fun unmarkedSetIsUndoneAgain() {
        val p = passes(w.fact(a, 1), w.fact(b, 1, done = false))
        assertEquals(1, p.currentPass)
        assertEquals(1, p.next(b)?.setNo)
    }

    @Test
    fun addedSetExtendsItem() {
        // «+ подход» к тяге: третий подход без цели
        val added = w.fact(b, 3, done = false)
        val p = passes(added)
        assertEquals(listOf(1, 2, 3), p.rows(b).map { it.setNo })
        assertNull(p.rows(b).last().target)
        assertEquals(3, p.passCount)
        // Проход 3 теперь закрывается только двумя отметками
        val marked = passes(w.fact(a, 3), added)
        assertTrue(!marked.passClosed(3))
    }

    @Test
    fun deletedSetHidesPlannedRow() {
        // Убрали второй подход тяги: проход 2 закрывает один присед
        val p = passes(w.fact(a, 1), w.fact(b, 1), w.fact(b, 2, done = false, deleted = true), w.fact(a, 2))
        assertEquals(listOf(1), p.rows(b).map { it.setNo })
        assertTrue(p.passClosed(2))
        assertEquals(3, p.currentPass)
    }

    @Test
    fun removedItemIsIgnored() {
        val blk = block(a, b.copy(removed = true))
        val p = BlockPasses(blk, listOf(workout(blk).fact(a, 1)))
        assertEquals(listOf(a), p.items)
        assertEquals(Rest(RestKind.AFTER_PASS, 120), p.restAfter(a, 1))
    }

    @Test
    fun otherBlocksFactsAreIgnored() {
        val other = item(squat, TargetSet())
        val w2 = workout(circle, block(other))
        val p = BlockPasses(circle, listOf(w2.fact(other, 1)))
        assertEquals(1, p.currentPass)
        assertEquals(1, p.next(a)?.setNo)
    }
}
