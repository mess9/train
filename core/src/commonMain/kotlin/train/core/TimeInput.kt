package train.core

/**
 * Ввод времени как на микроволновке: цифры заполняют `м:сс` справа. Набрал `1`, `3`, `0` — стало
 * `1:30`, полторы минуты. Стёр цифру — сдвиг обратно: `0:13`. Двоеточие набирать не нужно, его нет
 * на цифровой клавиатуре; набранное с двоеточием тоже понимается.
 *
 * Секунды больше 59 не запрещены, как на микроволновке: `90` — 90 с. Показ после ввода — [clock].
 */
object TimeInput {
    /** Цифр не больше четырёх — до `99:99`. Лишние не принимаются, как на микроволновке. */
    const val MAX_DIGITS = 4

    /** Набранное в поле → цифры ввода: без ведущих нулей, не длиннее [MAX_DIGITS]. */
    fun digits(text: String): String = text.filter { it in '0'..'9' }.trimStart('0').take(MAX_DIGITS)

    /** Что показать в поле, пока набирается: `""`, `0:05`, `1:30`, `13:00`. */
    fun text(digits: String): String {
        if (digits.isEmpty()) return ""
        val d = digits.padStart(3, '0')
        return d.dropLast(2) + ":" + d.takeLast(2)
    }

    /** Секунды набранного: `130` → 90, `90` → 90; пусто — `null`. */
    fun seconds(digits: String): Int? {
        if (digits.isEmpty()) return null
        val n = digits.toInt()
        return n / 100 * 60 + n % 100
    }

    /** Поле с набранным текстом → секунды: то же, что [digits] и [seconds] подряд. */
    fun parse(text: String): Int? = seconds(digits(text))

    /** Показ секунд: 90 → `1:30`, 5 → `0:05`, 3725 → `62:05`. */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Секунды → цифры ввода, чтобы поле продолжало набор с показанного значения: 90 → `130`. */
    fun digitsOf(seconds: Int): String = clock(seconds).filter { it != ':' }.trimStart('0').take(MAX_DIGITS)
}
