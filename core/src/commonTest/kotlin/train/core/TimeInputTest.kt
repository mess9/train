package train.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeInputTest {
    /** Набор по одной цифре, как в поле: каждый раз берётся показанный текст и дописывается цифра. */
    private fun type(vararg keys: Char): String = keys.fold("") { shown, k -> TimeInput.text(TimeInput.digits(shown + k)) }

    @Test
    fun digitsFillMinutesAndSecondsFromTheRight() {
        assertEquals("0:01", type('1'))
        assertEquals("0:13", type('1', '3'))
        assertEquals("1:30", type('1', '3', '0'))
        assertEquals("13:00", type('1', '3', '0', '0'))
        assertEquals(90, TimeInput.parse("1:30"))
        assertEquals(90, TimeInput.parse("130"))
    }

    @Test
    fun fifthDigitIsIgnored() {
        assertEquals("13:00", type('1', '3', '0', '0', '5'))
    }

    @Test
    fun backspaceShiftsBack() {
        // Стёрли последний символ «1:30» — осталось «1:3»
        assertEquals("0:13", TimeInput.text(TimeInput.digits("1:3")))
        assertEquals("0:01", TimeInput.text(TimeInput.digits("0:1")))
        assertEquals("", TimeInput.text(TimeInput.digits("0:0")))
    }

    @Test
    fun leadingZerosDoNotCount() {
        assertEquals("0:45", type('0', '4', '5'))
        assertEquals(45, TimeInput.parse("0045"))
    }

    @Test
    fun secondsAboveFiftyNineAreSecondsLikeOnMicrowave() {
        assertEquals(90, TimeInput.parse("90"))
        assertEquals(150, TimeInput.parse("190"))
    }

    @Test
    fun emptyIsNoValue() {
        assertNull(TimeInput.parse(""))
        assertNull(TimeInput.parse("0"))
        assertNull(TimeInput.parse(":"))
    }

    @Test
    fun clockShowsMinutesAndSeconds() {
        assertEquals("1:30", TimeInput.clock(90))
        assertEquals("0:05", TimeInput.clock(5))
        assertEquals("2:00", TimeInput.clock(120))
        assertEquals("62:05", TimeInput.clock(3725))
        assertEquals("0:00", TimeInput.clock(0))
    }

    @Test
    fun shownValueContinuesTyping() {
        assertEquals("130", TimeInput.digitsOf(90))
        assertEquals("5", TimeInput.digitsOf(5))
        assertEquals("", TimeInput.digitsOf(0))
        // Значение 90 с показано как «1:30»; дописали 5 — «13:05»
        assertEquals("13:05", TimeInput.text(TimeInput.digits(TimeInput.clock(90) + "5")))
    }
}
