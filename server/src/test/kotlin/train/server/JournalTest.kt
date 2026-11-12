package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.ExerciseHistory
import train.core.JournalEntry
import train.core.JournalPut
import train.core.Purged
import train.core.Trash
import train.core.Trend
import train.core.WeekSummary
import train.core.monday
import train.core.newId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Журнал тренера, история упражнений и недели (ADR 0014). */
@QuarkusTest
class JournalTest {
    @Inject
    lateinit var users: Users

    @Test
    fun journalPutListDeleteAndPurge() {
        val api = users.newApi()
        val bench = api.exercise()
        val w = api.startFrom(api.plan(bench), bench)
        val id = newId()
        val put = JournalPut(
            w.date!!, "  держать 40 кг до 10×2  ", " два ✕ на 10-м ", " три ✓ подряд ",
            workoutId = w.id, exerciseIds = listOf(bench.id, bench.id),
        )
        val e: JournalEntry = api.put("/api/journal/$id", put)
        assertEquals(listOf("держать 40 кг до 10×2", "два ✕ на 10-м", "три ✓ подряд"), listOf(e.decision, e.reason, e.review))
        assertEquals(listOf(bench.id), e.exerciseIds)
        // Повтор — та же запись, опечатка — правка на месте
        api.put<JournalPut, JournalEntry>("/api/journal/$id", put)
        api.put<JournalPut, JournalEntry>("/api/journal/$id", put.copy(decision = "держать 40"))
        api.put<JournalPut, JournalEntry>("/api/journal/${newId()}", JournalPut(w.date!!, "общая", "r", "v"))
        assertEquals(listOf("держать 40"), api.get<List<JournalEntry>>("/api/journal?exercise=${bench.id}").map { it.decision })
        assertEquals(2, api.get<List<JournalEntry>>("/api/journal?from=${w.date}&to=${w.date}").size)

        // Все три текста обязательны; ссылки — только на своё
        for (bad in listOf(put.copy(decision = " "), put.copy(reason = ""), put.copy(review = "\n"))) {
            api.put<JournalPut, ApiError>("/api/journal/${newId()}", bad, status = 400)
        }
        api.put<JournalPut, ApiError>("/api/journal/${newId()}", put.copy(workoutId = newId()), status = 400)
        api.put<JournalPut, ApiError>("/api/journal/${newId()}", put.copy(revises = newId()), status = 400)
        api.put<JournalPut, ApiError>("/api/journal/$id", put.copy(revises = id), status = 400)
        // Чужая запись не видна, не перезаписывается и не пересматривается
        val other = users.newApi()
        assertEquals(404, other.status("/api/journal/$id"))
        other.put<JournalPut, ApiError>("/api/journal/$id", JournalPut(w.date!!, "чужое", "r", "v"), status = 404)
        other.put<JournalPut, ApiError>("/api/journal/${newId()}", JournalPut(w.date!!, "чужое", "r", "v", revises = id), status = 400)

        // Пересмотр — новая запись; старая уходит из действующих
        val next = newId()
        api.put<JournalPut, JournalEntry>("/api/journal/$next", put.copy(decision = "42 кг", revises = id))
        assertEquals(next, api.get<JournalEntry>("/api/journal/$id").revisedBy)
        assertEquals(setOf(next), api.get<List<JournalEntry>>("/api/journal?active=true&exercise=${bench.id}").map { it.id }.toSet())
        // Пересмотр в корзине — решение снова действует
        api.spec().delete("/api/journal/$next").then().statusCode(204)
        assertNull(api.get<JournalEntry>("/api/journal/$id").revisedBy)
        assertEquals(listOf(next), api.get<List<JournalEntry>>("/api/journal?deleted=true").map { it.id })
        assertEquals(next, api.post<Unit, JournalEntry>("/api/journal/$next/restore", Unit).id)

        api.spec().delete("/api/journal/$id").then().statusCode(204)
        assertNotNull(api.get<JournalEntry>("/api/journal/$id").deletedAt)
        assertEquals(2, api.get<List<JournalEntry>>("/api/journal").size)
        assertEquals(listOf(id), api.get<Trash>("/api/trash").journal.map { it.id })
        assertNull(api.post<Unit, JournalEntry>("/api/journal/$id/restore", Unit).deletedAt)
        assertEquals(404, other.spec().post("/api/journal/$id/restore").then().extract().statusCode())
        api.spec().delete("/api/journal/$id").then().statusCode(204)
        assertEquals(1, api.post<Unit, Purged>("/api/trash/purge", Unit).journal)
        assertEquals(404, api.status("/api/journal/$id"))
        assertNull(api.get<JournalEntry>("/api/journal/$next").revises)
    }

    @Test
    fun historyAndWeeks() {
        val api = users.newApi()
        val bench = api.exercise()
        val now = Clock.System.now()
        val old = api.startFrom(api.plan(bench), bench, startedAt = now - 3.days)
        api.putSet(api.fact(old, 1, weight = 40.0, reps = 10), baseRev = 0)
        api.putWorkout(old.copy(finishedAt = old.startedAt + 30.minutes), baseRev = 1)
        val w = api.startFrom(api.plan(bench), bench, startedAt = now - 60.minutes)
        api.putSet(api.fact(w, 1, weight = 42.0, reps = 10), baseRev = 0)
        api.putSet(api.fact(w, 2, weight = 42.0, reps = 8), baseRev = 0)
        api.put<JournalPut, JournalEntry>("/api/journal/${newId()}", JournalPut(w.date!!, "жим растёт", "42 кг 10✓", "8 повторов", exerciseIds = listOf(bench.id)))

        // Без ids — упражнения основных блоков за месяц
        for (path in listOf("/api/history?ids=${bench.id}", "/api/history")) {
            val h = api.get<List<ExerciseHistory>>(path).single()
            assertEquals(bench.id, h.exerciseId)
            assertEquals(2, h.sessions)
            assertEquals(listOf(Trend.UP, null), h.recent.map { it.trend })
            assertEquals(listOf(10, 8), h.recent[0].sets.map { it.reps })
            assertEquals(listOf("жим растёт"), h.journal.map { it.decision })
        }
        assertEquals(1, api.get<List<ExerciseHistory>>("/api/history?ids=${bench.id}&recent=1").single().earlier.size)
        val window = api.get<List<ExerciseHistory>>("/api/history?ids=${bench.id}&recent=1&since=${old.date}").single()
        assertEquals(2, window.recent.size)
        assertTrue(window.earlier.isEmpty())
        assertTrue(users.newApi().get<List<ExerciseHistory>>("/api/history").isEmpty())
        api.get<ApiError>("/api/history?ids=нет", status = 400)

        val today = w.date!!
        val ws = api.get<List<WeekSummary>>("/api/stats/weeks")
        assertEquals(8, ws.size)
        assertEquals(today.monday(), ws.last().from)
        assertEquals(3, ws.sumOf { it.workSets })
        assertEquals(2, ws.sumOf { it.workouts })
        api.get<ApiError>("/api/stats/weeks?from=$today&to=2020-01-01", status = 400)
    }
}
