package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PhotoComparisonTest {
    private val utc = TimeZone.UTC
    private fun shot(day: String, pose: String? = "front") = Instant.parse("${day}T12:00:00Z").let {
        Photo(newId(), it, "image/png", 1, "hash", pose = pose, createdAt = it)
    }
    private fun weight(day: String, value: Double = 80.0) = Measurement(newId(), BODYWEIGHT, value, "kg", Instant.parse("${day}T12:00:00Z"))

    @Test
    fun groupsExcludeTrashAndSinglesAndKeepImportedAndCustomPoses() {
        val a = shot("2026-11-01")
        val b = shot("2026-11-02")
        val imported = listOf(shot("2026-11-03", null), shot("2026-11-04", " "))
        val custom = listOf(shot("2026-11-05", "Мой ракурс"), shot("2026-11-06", "Мой ракурс"))
        val groups = comparisonGroups(buildList {
            add(a)
            add(b)
            add(shot("2026-11-23", "side"))
            add(shot("2026-11-24").copy(deletedAt = T0))
            addAll(imported)
            addAll(custom)
        })
        assertEquals(listOf("Мой ракурс", null, "front"), groups.map { it.pose })
        assertEquals(listOf(b, a), groups.last().photos)
        assertTrue(comparisonGroups(listOf(a, b.copy(deletedAt = T0))).isEmpty())
    }

    @Test
    fun presetsUseCalendarMonthsIncludingLeapYearAndOnlyEarlierPhotos() {
        val after = shot("2024-03-31")
        val feb = shot("2024-02-29")
        val jan = shot("2024-01-31")
        val photos = listOf(after, feb, jan, shot("2024-04-01"))
        assertEquals(feb, photoMonthsBefore(photos, after, 1, utc))
        assertEquals(jan, photoMonthsBefore(photos, after, 2, utc))
        assertFailsWith<IllegalArgumentException> { photoMonthsBefore(photos, after, 0, utc) }
    }

    @Test
    fun equalTimestampsRemainAGroupButHaveNoStrictlyEarlierPreset() {
        val first = shot("2026-11-01")
        val second = shot("2026-11-01")
        assertEquals(1, comparisonGroups(listOf(first, second)).size)
        assertNull(photoMonthsBefore(listOf(first, second), first, 1, utc))
        assertNull(photoMonthsBefore(emptyList(), first, 1, utc))
    }

    @Test
    fun calendarNearestPrefersEarlierOnTieAndUsesLocalDay() {
        val early = shot("2026-11-10")
        val late = shot("2026-11-12")
        assertEquals(early, nearestPhoto(listOf(late, early), LocalDate(2026, 11, 11), utc))
        assertEquals(late, nearestPhoto(listOf(late, early.copy(deletedAt = T0)), LocalDate(2026, 11, 11), utc))
        assertNull(nearestPhoto(emptyList(), LocalDate(2026, 11, 11), utc))
        val localFirst = early.copy(takenAt = Instant.parse("2026-11-30T22:00:00Z"))
        val localNext = late.copy(takenAt = Instant.parse("2026-12-01T23:00:00Z"))
        assertEquals(localFirst, nearestPhoto(listOf(localNext, localFirst), LocalDate(2026, 12, 1), TimeZone.of("Asia/Yerevan")))
    }

    @Test
    fun weightUsesNearestWithinSevenDaysAndNeverInventsMissingValues() {
        val photo = shot("2026-11-15")
        val earlier = weight("2026-11-14")
        val later = weight("2026-11-16")
        assertEquals(earlier, photoWeight(photo, listOf(later, earlier), utc))
        assertEquals(later, photoWeight(photo, listOf(weight("2026-11-08"), later), utc))
        assertEquals(80.0, photoWeight(photo, listOf(weight("2026-11-08")), utc)?.value)
        assertNull(photoWeight(photo, listOf(
            weight("2026-11-07"), weight("2026-11-23"), earlier.copy(deleted = true),
            earlier.copy(type = "waist"), earlier.copy(unit = "lb"), earlier.copy(value = Double.NaN), earlier.copy(value = -1.0),
        ), utc))
    }

    @Test
    fun sevenDayWindowUsesPhoneCalendarAtMidnight() {
        val photo = shot("2026-11-15").copy(takenAt = Instant.parse("2026-11-15T00:00:00Z"))
        val m = weight("2026-11-07").copy(measuredAt = Instant.parse("2026-11-07T23:00:00Z"))
        assertNull(photoWeight(photo, listOf(m), utc))
        assertEquals(m, photoWeight(photo, listOf(m), TimeZone.of("Asia/Yerevan")))
    }

    @Test
    fun intervalClampsMonthEndsAndKeepsDaysAfterWholeMonths() {
        assertEquals(PhotoInterval(1, 0), photoInterval(LocalDate(2025, 1, 31), LocalDate(2025, 2, 28)))
        assertEquals(PhotoInterval(1, 0), photoInterval(LocalDate(2024, 1, 31), LocalDate(2024, 2, 29)))
        assertEquals(PhotoInterval(3, 2), photoInterval(LocalDate(2026, 8, 21), LocalDate(2026, 11, 23)))
        assertEquals(PhotoInterval(0, 0), photoInterval(LocalDate(2026, 11, 23), LocalDate(2026, 11, 23)))
        assertFailsWith<IllegalArgumentException> { photoInterval(LocalDate(2026, 11, 24), LocalDate(2026, 11, 23)) }
    }

    @Test
    fun calendarHasMondayColumnsLeapDayAndFullWeeks() {
        val leap = calendarWeeks(LocalDate(2024, 2, 15))
        assertEquals(29, leap.flatten().filterNotNull().size)
        assertEquals(LocalDate(2024, 2, 1), leap.first()[3])
        val six = calendarWeeks(LocalDate(2026, 11, 23))
        assertEquals(6, six.size)
        assertEquals(LocalDate(2026, 11, 1), six.first()[6])
        assertEquals((1..30).toList(), six.flatten().filterNotNull().map { it.day })
        assertTrue((leap + six).all { it.size == 7 })
    }
}
