package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class MeasurementTrendsTest {
    private val from = LocalDate(2026, 11, 1)
    private fun measure(day: Int, value: Double, type: String = "waist") =
        Measurement(newId(), type, value, "cm", Instant.parse("2026-11-01T12:00:00Z") + day.days)

    @Test
    fun periodSelectsOnlyThisTypeSortsAndIgnoresDeletedOrInvalidValues() {
        val first = measure(0, 86.0)
        val last = measure(20, 84.5)
        val (points, inPeriod) = trendPoints(listOf(
            last, measure(-10, 87.0), measure(4, 10.0, "biceps"), first,
            measure(8, 90.0).copy(deleted = true), measure(9, Double.NaN),
        ), "waist", from)
        assertTrue(inPeriod)
        assertEquals(listOf(first, last), points)
        assertEquals(-1.5, points.last().value - points.first().value)
    }

    @Test
    fun sparsePeriodFallsBackToLastTwelveIncludingTheLatestPoint() {
        val all = (0..19).map { measure(it - 19, 100.0 - it) }
        val (points, inPeriod) = trendPoints(all.reversed(), "waist", from)
        assertFalse(inPeriod)
        assertEquals(all.takeLast(12), points)
        assertEquals(-11.0, points.last().value - points.first().value)
    }

    @Test
    fun emptySingleAndConstantSeriesStayHonest() {
        assertEquals(emptyList<Measurement>() to false, trendPoints(emptyList(), "waist", from))
        val one = measure(0, 84.0)
        assertEquals(listOf(one) to false, trendPoints(listOf(one), "waist", from))
        val (same, inPeriod) = trendPoints(listOf(one, measure(1, 84.0)), "waist", from)
        assertTrue(inPeriod)
        assertEquals(0.0, same.last().value - same.first().value)
    }

    @Test
    fun calendarBoundaryUsesPhoneTimezone() {
        val beforeUtc = measure(0, 86.0).copy(measuredAt = Instant.parse("2026-10-31T22:00:00Z"))
        val afterUtc = measure(0, 85.0)
        assertFalse(trendPoints(listOf(beforeUtc, afterUtc), "waist", from).second)
        assertTrue(trendPoints(listOf(beforeUtc, afterUtc), "waist", from, TimeZone.of("Asia/Yerevan")).second)
    }

    @Test
    fun bodyFatMethodsAndBodyweightDoNotMix() {
        val all = listOf(
            measure(0, 20.0, BODYFAT), measure(1, 19.0, BODYFAT),
            measure(0, 18.0, BODYFAT_PHOTO), measure(1, 18.5, BODYFAT_PHOTO),
            measure(0, 80.0, BODYWEIGHT), measure(1, 80.5, BODYWEIGHT),
        )
        assertEquals(listOf(20.0, 19.0), trendPoints(all, BODYFAT, from).first.map { it.value })
        assertEquals(listOf(18.0, 18.5), trendPoints(all, BODYFAT_PHOTO, from).first.map { it.value })
        assertEquals(listOf(80.0, 80.5), trendPoints(all, BODYWEIGHT, from).first.map { it.value })
    }
}
