package train.server

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import train.core.ApiError
import train.core.HealthExcluded
import train.core.HealthExclusion
import train.core.HealthExclusionCreate
import train.core.HealthRecord
import train.core.HealthReport
import train.core.HealthType
import train.core.HealthUpload
import train.core.HealthUploaded
import train.core.SleepStage
import train.core.SleepStageType
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Сон, пульс, шаги с телефона (ADR 0015). */
@QuarkusTest
class HealthTest {
    @Inject
    lateinit var users: Users

    private val off = 4 * 3600
    private val zone = UtcOffset(seconds = off).asTimeZone()
    private val band = "com.xiaomi.wearable"

    private fun at(d: Int, h: Int): Instant = LocalDateTime(2026, 10, d, h, 0).toInstant(zone)

    private fun night(id: String, d: Int) = HealthRecord(
        id, HealthType.SLEEP, band, at(d - 1, 23), at(d, 7), off,
        stages = listOf(SleepStage(at(d - 1, 23), at(d, 1), SleepStageType.DEEP), SleepStage(at(d, 1), at(d, 7), SleepStageType.LIGHT)),
    )

    private fun steps(id: String, d: Int, n: Int) = HealthRecord(id, HealthType.STEPS, band, at(d, 10), at(d, 11), off, value = n.toDouble())

    private fun TestApi.report(from: String, to: String): HealthReport = get("/api/health?from=$from&to=$to")

    @Test
    fun uploadIsIdempotentReportCountsDays() {
        val api = users.newApi()
        val upload = HealthUpload(listOf(night("n10", 10), steps("s10", 10, 3000), night("n11", 11), steps("s11", 11, 500)))
        assertEquals(HealthUploaded(4, 0, 0), api.post<HealthUpload, HealthUploaded>("/api/health/records", upload))
        // Повтор и правка записи — замена, не дубль; одна запись дважды в пачке — последняя
        api.post<HealthUpload, HealthUploaded>("/api/health/records", upload)
        val r = api.post<HealthUpload, HealthUploaded>(
            "/api/health/records", HealthUpload(listOf(steps("s11", 11, 600), steps("s11", 11, 700)), deleted = listOf("n11", "нет")),
        )
        assertEquals(HealthUploaded(1, 1, 0), r)

        val rep = api.report("2026-10-10", "2026-10-12")
        assertEquals(listOf(LocalDate(2026, 10, 10), LocalDate(2026, 10, 11), LocalDate(2026, 10, 12)), rep.days.map { it.date })
        val d10 = rep.days[0]
        assertEquals(480, d10.sleep!!.asleepMin)
        assertEquals(120, d10.sleep!!.deepMin)
        assertEquals(3000, d10.steps)
        assertNull(rep.days[1].sleep)
        assertEquals(700, rep.days[1].steps)
        assertEquals(listOf(band), rep.sources)

        // Чужие записи не видны
        assertTrue(users.newApi().report("2026-10-10", "2026-10-12").days.all { it.sleep == null && it.steps == null })
    }

    @Test
    fun exclusionRemovesAndRejectsRecords() {
        val api = users.newApi()
        api.post<HealthUpload, HealthUploaded>(
            "/api/health/records", HealthUpload(listOf(night("a", 15), steps("b", 15, 200), steps("c", 16, 5000), night("p", 16).copy(source = "phone"))),
        )
        // Ночь на 15-е началась 14-го вечером — её день по началу, 14-е
        val ex: HealthExcluded = api.post("/api/health/exclusions", HealthExclusionCreate(LocalDate(2026, 10, 14), LocalDate(2026, 10, 15), band, " часы на другом "))
        assertEquals(2, ex.removed)
        assertEquals("часы на другом", ex.exclusion.note)
        val again = api.post<HealthUpload, HealthUploaded>("/api/health/records", HealthUpload(listOf(night("a", 15), steps("d", 16, 1))))
        assertEquals(HealthUploaded(1, 0, 1), again)

        val rep = api.report("2026-10-15", "2026-10-16")
        assertTrue(rep.days[0].excluded)
        assertNull(rep.days[0].sleep)
        // Другой источник исключение не трогает
        assertEquals("phone", rep.days[1].sleep!!.source)
        assertEquals(listOf(ex.exclusion), rep.exclusions)
        assertEquals(listOf(ex.exclusion), api.get<List<HealthExclusion>>("/api/health/exclusions"))

        assertEquals(404, users.newApi().spec().delete("/api/health/exclusions/${ex.exclusion.id}").statusCode)
        api.spec().delete("/api/health/exclusions/${ex.exclusion.id}").then().statusCode(204)
        assertEquals(emptyList<HealthExclusion>(), api.get<List<HealthExclusion>>("/api/health/exclusions"))
        assertEquals(HealthUploaded(1, 0, 0), api.post<HealthUpload, HealthUploaded>("/api/health/records", HealthUpload(listOf(night("a", 15)))))
    }

    @Test
    fun badInputIsRejected() {
        val api = users.newApi()
        val ok = steps("x", 1, 1)
        for (bad in listOf(
            ok.copy(id = " "),
            ok.copy(source = ""),
            ok.copy(end = ok.start - 1.hours),
            ok.copy(hrSec = listOf(0, 1), hrBpm = listOf(60)),
            ok.copy(zoneOffset = 30 * 3600),
        )) {
            api.post<HealthUpload, ApiError>("/api/health/records", HealthUpload(listOf(bad)), status = 400)
        }
        api.get<ApiError>("/api/health?from=2026-10-10&to=2026-10-01", status = 400)
        api.get<ApiError>("/api/health?from=2025-01-01&to=2026-10-01", status = 400)
        api.post<HealthExclusionCreate, ApiError>(
            "/api/health/exclusions", HealthExclusionCreate(LocalDate(2026, 10, 2), LocalDate(2026, 10, 1)), status = 400,
        )
        // Без дат — последние две недели
        assertEquals(14, api.get<HealthReport>("/api/health").days.size)
    }
}
