package train.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun unknownFieldsAreIgnored() {
        val info = TrainJson.decodeFromString<ServerInfo>("""{"name":"train","version":"1","extra":true}""")
        assertEquals(ServerInfo("train", "1"), info)
    }

    @Test
    fun agentPlanNeedsNoIds() {
        val json = """
            {"date":"2026-11-04","title":"Круг","body":{"blocks":[
              {"role":"WARMUP","items":[{"exerciseId":"019a4a2e-0000-7000-8000-000000000001","recordType":"TIME","sets":[{"seconds":120}]}]},
              {"items":[
                {"exerciseId":"019a4a2e-0000-7000-8000-000000000002","sets":[{"weight":12.5,"repsMin":8,"repsMax":10},{"weight":15,"repsMax":8}],"restSec":30},
                {"exerciseId":"019a4a2e-0000-7000-8000-000000000003","sets":[{"repsMax":12}]}
              ],"restAfterPassSec":120}
            ]}}
        """.trimIndent()
        val plan = TrainJson.decodeFromString<PlanPut>(json)
        assertEquals(LocalDate(2026, 11, 4), plan.date)
        val main = plan.body.blocks[1]
        assertEquals(BlockRole.MAIN, main.role)
        assertEquals(null, main.items[1].sets[0].weight)
        val ids = plan.body.blocks.flatMap { b -> listOf(b.id) + b.items.flatMap { i -> listOf(i.id) + i.sets.map { it.id } } }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun nullsAreNotWritten() {
        val text = TrainJson.encodeToString(TargetSet(weight = 10.0))
        assertTrue("weight" in text)
        assertFalse("reps" in text)
    }

    @Test
    fun snapshotFillsDefaultsFromCatalog() {
        val rope = exercise("Скакалка", type = RecordType.TIME)
        val lunge = exercise("Выпады", perSide = true)
        val body = PlanBody(listOf(Block(items = listOf(
            BlockItem(exerciseId = rope.id, sets = listOf(TargetSet(seconds = 60))),
            BlockItem(exerciseId = lunge.id, recordType = RecordType.REPS, sets = listOf(TargetSet())),
        ))))
        val layout = body.snapshot(listOf(rope, lunge).associateBy { it.id }::get)
        val (r, l) = layout.blocks[0].items
        assertEquals(RecordType.TIME to false, r.recordType to r.perSide)
        assertEquals(RecordType.REPS to true, l.recordType to l.perSide)
        assertEquals(body.blocks[0].items[0].sets, r.sets)
    }

    @Test
    fun planStatus() {
        val d = LocalDate(2026, 11, 3)
        assertEquals(PlanStatus.PLANNED, planStatus(d, null, d))
        assertEquals(PlanStatus.OVERDUE, planStatus(d, null, LocalDate(2026, 11, 4)))
        assertEquals(PlanStatus.STARTED, planStatus(d, T0, LocalDate(2026, 11, 4)))
    }

    @Test
    fun idsAreTimeOrdered() {
        val a = newId()
        val b = newId()
        assertNotEquals(a, b)
        assertTrue(a.toString() < b.toString())
        assertEquals('7', a.toString()[14])
    }
}
