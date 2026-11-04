package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddBlockTest {
    private val press = exercise("Жим")
    private val stretch = exercise("Растяжка", type = RecordType.TIME)
    private val burpee = exercise("Бёрпи", type = RecordType.REPS)
    private val warm = block(item(exercise("Скакалка", type = RecordType.TIME), TargetSet(seconds = 120)), role = BlockRole.WARMUP)
    private val circle = block(item(press, TargetSet(weight = 14.0, repsMax = 12)))
    private val user = newId()

    private fun started(vararg blocks: LayoutBlock) =
        Session(user).start(null, WorkoutLayout(blocks.toList()), listOf(press), emptyList(), T0)

    private val Session.blocks get() = workout!!.layout.blocks

    @Test
    fun cooldownGoesLastWithOneSet() {
        val s = started(warm, circle).addBlock(BlockRole.COOLDOWN, stretch)
        assertEquals(listOf(BlockRole.WARMUP, BlockRole.MAIN, BlockRole.COOLDOWN), s.blocks.map { it.role })
        val cool = s.blocks.last()
        val item = cool.items.single()
        assertTrue(item.added)
        assertEquals(stretch.id, item.exerciseId)
        assertEquals(listOf(1), s.sets.filter { it.blockId == cool.id }.map { it.setNo })
        assertTrue(s.hasCooldown)
        assertEquals(stretch, s.exercise(stretch.id), "упражнение блока есть и без сети")
    }

    @Test
    fun extraCircuitGoesBeforeCooldownWithPasses() {
        val cool = block(item(stretch, TargetSet(seconds = 60)), role = BlockRole.COOLDOWN)
        var s = started(warm, circle, cool).addBlock(BlockRole.MAIN, burpee, sets = 3)
        assertEquals(listOf(BlockRole.WARMUP, BlockRole.MAIN, BlockRole.MAIN, BlockRole.COOLDOWN), s.blocks.map { it.role })
        val extra = s.blocks[2]
        assertEquals(3, BlockPasses(extra, s.sets).passCount)

        // Второе упражнение круга получает по числу проходов, как обычно
        s = s.addItem(extra.id, press)
        val added = s.block(extra.id)!!.items[1]
        assertEquals(listOf(1, 2, 3), s.sets.filter { it.itemId == added.id }.map { it.setNo })
    }

    @Test
    fun valuesComeFromLastTime() {
        val last = SetFact(newId(), newId(), newId(), newId(), 1, exerciseId = burpee.id, recordType = RecordType.REPS, reps = 15, done = true, doneAt = T0)
        val s = started(circle).addBlock(BlockRole.MAIN, burpee, listOf(last), sets = 2)
        val facts = s.sets.filter { it.exerciseId == burpee.id }.sortedBy { it.setNo }
        assertEquals(listOf(15, null), facts.map { it.reps })
    }

    @Test
    fun oneQueuedWorkoutPerAction() {
        val before = started(circle)
        val s = before.addBlock(BlockRole.COOLDOWN, stretch)
        val workouts = s.outbox.pending.map { it.record }.filterIsInstance<Workout>()
        assertEquals(1, workouts.size, "старт и добавление блока — одна запись тренировки в очереди")
        assertEquals(2, workouts.single().layout.blocks.size)
    }

    @Test
    fun noCooldownWhenItsItemsRemoved() {
        val cool = block(item(stretch, TargetSet(seconds = 60)), role = BlockRole.COOLDOWN)
        val s = started(circle, cool)
        assertTrue(s.hasCooldown)
        assertFalse(s.removeItem(cool.id, cool.items.single().id).hasCooldown)
    }

    @Test
    fun finishedWorkoutGetsNoBlocks() {
        val s = started(circle).finish(T0)
        assertEquals(s, s.addBlock(BlockRole.COOLDOWN, stretch))
    }
}
