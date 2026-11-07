package train.core

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyFatTest {
    /** Сверено с калькулятором: 2,5 + 12 + 20 мм, 37 лет — 11,2 %. */
    @Test
    fun jp3() = assertEquals(11.2, bodyFatJp3(2.5, 12.0, 20.0, 37))

    @Test
    fun ageCountsBirthday() {
        val birth = LocalDate(1989, 5, 9)
        assertEquals(37, ageAt(birth, LocalDate(2026, 11, 7)))
        assertEquals(37, ageAt(birth, LocalDate(2027, 5, 8)))
        assertEquals(38, ageAt(birth, LocalDate(2027, 5, 9)))
    }

    @Test
    fun olderAndThickerIsFatter() {
        assertTrue(bodyFatJp3(10.0, 20.0, 15.0, 30) > bodyFatJp3(8.0, 16.0, 12.0, 30))
        assertTrue(bodyFatJp3(10.0, 20.0, 15.0, 50) > bodyFatJp3(10.0, 20.0, 15.0, 30))
    }
}
