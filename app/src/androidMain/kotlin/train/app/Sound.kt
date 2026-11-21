package train.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Сигнал конца отдыха — тот же «щёлк-дзынь», что у PWA (`beep` в index.html): удар — короткий шум
 * через полосу, звон — негармоничные обертоны стальной пластинки. Там его собирает Web Audio,
 * здесь — те же формулы в PCM один раз; компрессор заменён поджатием пиков и нормировкой.
 */
internal object Sound {
    private const val RATE = 44100

    private val pcm: ShortArray by lazy { render() }

    fun beep() {
        runCatching {
            val data = pcm
            val track = AudioTrack.Builder()
                // Как звук страницы у PWA — громкость медиа
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            track.write(data, 0, data.size)
            track.play()
            AndroidHost.main.postDelayed({ track.release() }, data.size * 1000L / RATE + 300)
        }
    }

    private fun render(): ShortArray {
        val out = FloatArray((RATE * 0.45).toInt())
        val noise = FloatArray((RATE * 0.03).toInt()).also { n ->
            val r = Random(7)
            for (i in n.indices) n[i] = ((r.nextFloat() * 2 - 1) * (1.0 - i.toDouble() / n.size).pow(4)).toFloat()
        }
        clack(out, 0.02, 1250.0, 0.8, noise) // взвод
        clack(out, 0.11, 1900.0, 1.0, noise) // щелчок со звоном
        // Компрессор Web Audio (порог −18 дБ, 8:1) поднимал громкость почти до предела: поджимаем
        // всё выше порога и нормируем пик
        val threshold = 0.126f
        for (i in out.indices) {
            val x = out[i] * 2.2f
            val a = abs(x)
            out[i] = if (a <= threshold) x else Math.copySign(threshold + (a - threshold) / 8, x)
        }
        val peak = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1f
        return ShortArray(out.size) { (out[it] / peak * 0.95f * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Удар и обертоны с момента [t] с, высота [pitch] Гц, уровень [level]. */
    private fun clack(out: FloatArray, t: Double, pitch: Double, level: Double, noise: FloatArray) {
        val start = (t * RATE).toInt()
        // Шум через полосовой фильтр (biquad, как BiquadFilter `bandpass` у Web Audio)
        val w0 = 2 * PI * pitch * 1.6 / RATE
        val alpha = sin(w0) / (2 * 1.4)
        val a0 = 1 + alpha
        val b0 = alpha / a0
        val b2 = -alpha / a0
        val a1 = -2 * cos(w0) / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in noise.indices) {
            val x = noise[i].toDouble()
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            val j = start + i
            if (j < out.size) out[j] += (y * 1.6 * level).toFloat()
        }
        // Обертоны: множитель частоты, амплитуда, длительность
        for ((k, amp, len) in listOf(Triple(1.0, 0.5, 0.22), Triple(2.76, 0.32, 0.14), Triple(5.4, 0.2, 0.08), Triple(8.93, 0.12, 0.05))) {
            val peak = amp * level
            val attack = 0.002
            val n = ((len + 0.02) * RATE).toInt()
            for (i in 0 until n) {
                val s = i.toDouble() / RATE
                // Экспоненциальные подъём и спад, как exponentialRampToValueAtTime
                val g = when {
                    s < attack -> 0.0001 * (peak / 0.0001).pow(s / attack)
                    s < len -> peak * (0.0001 / peak).pow((s - attack) / (len - attack))
                    else -> 0.0001
                }
                val j = start + i
                if (j < out.size) out[j] += (g * sin(2 * PI * pitch * k * s)).toFloat()
            }
        }
    }
}
