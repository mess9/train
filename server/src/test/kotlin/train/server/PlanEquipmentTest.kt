package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.EquipmentStep
import train.core.Plan
import train.core.newId

@QuarkusTest
class PlanEquipmentTest {
    @Inject
    lateinit var users: Users

    @Test
    fun storesStepsAcrossReadAndNewRevision() {
        val api = users.newApi()
        val ex = api.exercise()
        val put = TestApi.planPut(ex).let { it.copy(body = it.body.copy(equipment = listOf(
            EquipmentStep(setup = "гантели 2×12,5"),
            EquipmentStep(exerciseId = ex.id, at = "Круг 2", setup = "гриф 1×15"),
        ))) }
        val path = "/api/plans/${newId()}"
        val first = api.put<_, Plan>(path, put)
        assertEquals(put.body, first.body)
        assertEquals(first, api.get<Plan>(path))
        val second = api.put<_, Plan>(path, put.copy(baseVersion = first.version, body = put.body.copy(note = "правка")))
        assertEquals(first.version + 1, second.version)
        assertEquals(put.body.equipment, second.body.equipment)
        // Старые клиенты и планы могут прислать тело без equipment.
        assertTrue(api.put<_, Plan>(path, TestApi.planPut(ex)).body.equipment.isEmpty())
    }

    @Test
    fun rejectsExerciseOutsidePlanEvenWhenInOwnCatalog() {
        val api = users.newApi()
        val ex = api.exercise()
        val outside = api.exercise(nameRu = "Не в плане")
        val put = TestApi.planPut(ex)
        for (id in listOf(outside.id, newId())) {
            val bad = put.copy(body = put.body.copy(equipment = listOf(EquipmentStep(exerciseId = id, setup = "гантель 15"))))
            val path = "/api/plans/${newId()}"
            val error = api.put<_, ApiError>(path, bad, status = 400)
            assertTrue("equipment[0].exerciseId" in error.error)
            assertEquals(404, api.status(path))
        }
    }

    @Test
    fun rejectsBlankSetupWithoutChangingExistingPlan() {
        val api = users.newApi()
        val ex = api.exercise()
        val original = api.plan(ex)
        val path = "/api/plans/${original.id}"
        val put = TestApi.planPut(ex)
        for (setup in listOf("", " \n\t ")) {
            val bad = put.copy(body = put.body.copy(equipment = listOf(EquipmentStep(setup = setup))))
            val error = api.put<_, ApiError>(path, bad, status = 400)
            assertTrue("equipment[0].setup" in error.error)
            assertEquals(original, api.get<Plan>(path))
        }
    }
}
