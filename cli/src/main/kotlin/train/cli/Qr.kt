package train.cli

import io.nayuki.qrcodegen.QrCode

/** QR в терминале: две строки модулей на строку текста, светлые модули — символами блока. */
object Qr {
    private const val BORDER = 2

    fun render(text: String): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        // Тёмная тема терминала: символ — светлый, пробел — тёмный модуль. Рамка — светлая
        fun light(x: Int, y: Int) = x !in 0 until qr.size || y !in 0 until qr.size || !qr.getModule(x, y)
        val range = -BORDER until qr.size + BORDER
        return range.step(2).joinToString("\n") { y ->
            range.joinToString("") { x ->
                when (light(x, y) to light(x, y + 1)) {
                    true to true -> "█"
                    true to false -> "▀"
                    false to true -> "▄"
                    else -> " "
                }
            }
        }
    }
}
