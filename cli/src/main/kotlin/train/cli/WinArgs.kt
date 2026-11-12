package train.cli

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.nio.charset.Charset

/**
 * Аргументы командной строки без потерь на Windows.
 *
 * `java.exe` переводит командную строку в кодировку ANSI (у владельца — cp1251) до `main`: всё, чего в
 * ней нет (✓ × → ♥ ≈), приходит в `args` знаком «?» или похожей буквой, без ошибки. Исходный текст
 * берём у Windows — `GetCommandLineW` и `CommandLineToArgvW` — и подставляем только если каждый
 * восстановленный аргумент сводится к пришедшему (те же символы, а непредставимые в ANSI — на любом).
 * Не сошлось или WinAPI недоступен — остаются `args`, и про каждый аргумент с «?» — предупреждение.
 */
object WinArgs {
    fun fix(
        args: Array<String>,
        ansi: Charset? = ansiCharset(),
        commandLine: () -> List<String> = ::commandLine,
        warn: (String) -> Unit = { System.err.println(it) },
    ): Array<String> {
        if (ansi == null || args.isEmpty()) return args
        runCatching { restore(args, commandLine(), ansi) }.getOrNull()?.let { return it }
        args.filter { '?' in it }.forEach {
            warn("предупреждение: в аргументе «$it» есть «?» — символы вне ${ansi.name()} могли потеряться " +
                "по дороге в java.exe, а исходную строку Windows прочитать не удалось")
        }
        return args
    }

    /** Последние `args.size` слов исходной строки, если каждое сводится к своему аргументу; иначе null. */
    internal fun restore(args: Array<String>, wide: List<String>, ansi: Charset): Array<String>? {
        if (wide.size < args.size) return null
        val tail = wide.subList(wide.size - args.size, wide.size)
        return if (args.indices.all { lossy(tail[it], args[it], ansi) }) tail.toTypedArray() else null
    }

    /**
     * [narrow] — это [wide] после перевода в [ansi]: представимые символы совпадают, на месте
     * непредставимого — один любой знак («?» или похожая буква), у пары суррогатов — один или два.
     */
    internal fun lossy(wide: String, narrow: String, ansi: Charset): Boolean {
        val enc = ansi.newEncoder()
        fun from(w: Int, n: Int): Boolean {
            if (w == wide.length) return n == narrow.length
            val len = Character.charCount(wide.codePointAt(w))
            val ch = wide.substring(w, w + len)
            if (enc.canEncode(ch)) return narrow.startsWith(ch, n) && from(w + len, n + len)
            if (n >= narrow.length) return false
            return from(w + len, n + 1) || (len == 2 && n + 1 < narrow.length && from(w + len, n + 2))
        }
        return from(0, 0)
    }

    /** Кодировка, в которой java.exe отдал аргументы; null — не Windows или уже UTF-8 (потерь нет). */
    private fun ansiCharset(): Charset? {
        if (System.getProperty("os.name")?.startsWith("Windows") != true) return null
        val cs = System.getProperty("sun.jnu.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
        return cs?.takeIf { it != Charsets.UTF_8 }
    }

    internal interface Kernel32 : Library {
        fun GetCommandLineW(): Pointer
        fun LocalFree(p: Pointer): Pointer?
    }

    internal interface Shell32 : Library {
        fun CommandLineToArgvW(cmd: Pointer, argc: IntByReference): Pointer?
    }

    /** Вся командная строка процесса, словами: java.exe, его ключи, класс, затем аргументы. */
    private fun commandLine(): List<String> {
        val kernel = Native.load("kernel32", Kernel32::class.java)
        val shell = Native.load("shell32", Shell32::class.java)
        val argc = IntByReference()
        val argv = shell.CommandLineToArgvW(kernel.GetCommandLineW(), argc) ?: error("CommandLineToArgvW")
        try {
            return argv.getPointerArray(0, argc.value).map { it.getWideString(0) }
        } finally {
            kernel.LocalFree(argv)
        }
    }
}
