package train.cli

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WinArgsTest {
    private val cp1251 = Charset.forName("windows-1251")

    /** Командная строка java.exe из gym.bat: ключи, класс, затем аргументы. */
    private val java = listOf("C:\\jdk\\bin\\java.exe", "-Dstdout.encoding=UTF-8", "-classpath", "lib\\cli.jar", "train.cli.MainKt")

    @Test
    fun `кириллица сводится сама к себе, значки — к знаку вопроса`() {
        assertTrue(WinArgs.lossy("мёртвый жук", "мёртвый жук", cp1251))
        assertTrue(WinArgs.lossy("3× 12 ✓ → ♥", "3? 12 ? ? ?", cp1251))
        assertTrue(WinArgs.lossy("−1 %", "-1 %", cp1251), "непредставимый знак Windows может заменить похожим")
        assertFalse(WinArgs.lossy("abc", "abd", cp1251))
        assertFalse(WinArgs.lossy("✓ норм", "? нор", cp1251))
        assertFalse(WinArgs.lossy("норм", "норм?", cp1251))
    }

    @Test
    fun `пара суррогатов — один или два знака`() {
        assertTrue(WinArgs.lossy("💪 сила", "? сила", cp1251))
        assertTrue(WinArgs.lossy("💪 сила", "?? сила", cp1251))
        assertTrue(WinArgs.lossy("💪?", "??", cp1251), "настоящий «?» после значка")
    }

    @Test
    fun `аргументы берутся с конца исходной строки`() {
        val args = arrayOf("journal", "add", "--decision", "3? 12 ?")
        val wide = java + listOf("journal", "add", "--decision", "3× 12 ✓")
        assertContentEquals(arrayOf("journal", "add", "--decision", "3× 12 ✓"), WinArgs.fix(args, cp1251, { wide }, { error(it) }))
    }

    @Test
    fun `не сошлось — свои аргументы и предупреждение про знак вопроса`() {
        val args = arrayOf("journal", "add", "--decision", "3? 12")
        val wide = java + listOf("journal", "add", "--decision", "другое")
        assertNull(WinArgs.restore(args, wide, cp1251))
        val warnings = mutableListOf<String>()
        assertSame(args, WinArgs.fix(args, cp1251, { wide }, { warnings += it }))
        assertEquals(1, warnings.size)
        assertTrue("3? 12" in warnings[0])
    }

    @Test
    fun `WinAPI недоступен — свои аргументы, без «?» молча`() {
        val warnings = mutableListOf<String>()
        val args = arrayOf("plan", "get", "abc")
        assertSame(args, WinArgs.fix(args, cp1251, { error("нет kernel32") }, { warnings += it }))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `не Windows или UTF-8 — аргументы как есть`() {
        val args = arrayOf("journal", "add", "--decision", "?")
        assertSame(args, WinArgs.fix(args, null, { error("не звать") }, { error(it) }))
    }
}
