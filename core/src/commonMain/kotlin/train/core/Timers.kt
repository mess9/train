package train.core

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Полоска отдыха внизу экрана тренировки. `until == null` — открытый отдых: в плане его нет,
 * идёт счёт вверх от [since].
 */
data class RestTimer(val since: Instant, val until: Instant?, val total: Int, val label: String) {
    /** ±15 с: вперёд — от «сейчас», если отдых уже вышел. */
    fun shift(sec: Int, now: Instant): RestTimer {
        val u = until ?: return this
        return if (sec > 0) copy(until = maxOf(u, now) + sec.seconds, total = total + sec) else copy(until = u + sec.seconds)
    }
}

/**
 * Полоска после новой отметки подхода `setNo` у `item`; считать по состоянию, где отметка уже есть.
 * Отдых в плане есть — обратный отсчёт. Нет: в круге — открытый отдых (счёт вверх, переход между
 * упражнениями тоже отдых), у одиночного упражнения — полоски нет.
 */
fun BlockPasses.restTimer(item: LayoutItem, setNo: Int, now: Instant): RestTimer? {
    val rest = restAfter(item, setNo)
    // Проход бывает только в круге: у одиночного упражнения «после прохода» звучит странно
    val afterPass = rest.kind == RestKind.AFTER_PASS && circuit
    val sec = rest.seconds?.takeIf { it > 0 }
    return when {
        sec != null -> RestTimer(now, now + sec.seconds, sec, if (afterPass) "Отдых после прохода по плану · $sec с" else "Отдых по плану · $sec с")
        circuit && !finished -> RestTimer(now, null, 0, if (afterPass) "Отдых после прохода" else "Отдых между упражнениями")
        else -> null
    }
}

/** Таймер и секундомер из шапки тренировки: живут в памяти, пока открыто приложение. */
data class Tools(
    val tab: Int = 0,
    val timerDur: Int = 90,
    val timerLeft: Int = 90,
    val timerUntil: Instant? = null,
    val swAcc: Long = 0,
    val swStart: Instant? = null,
) {
    val timerRunning get() = timerUntil != null

    fun timerLeftMs(now: Instant): Long = timerUntil?.let { (it - now).inWholeMilliseconds.coerceAtLeast(0) } ?: (timerLeft * 1000L)

    /** Дошёл до нуля и ещё не перезапущен. */
    fun timerFinished(now: Instant) = timerRunning && timerLeftMs(now) <= 0

    fun timerPreset(sec: Int) = copy(timerDur = sec, timerLeft = sec, timerUntil = null)

    /** Старт / пауза / «заново» после нуля. */
    fun timerToggle(now: Instant): Tools = when {
        timerFinished(now) -> copy(timerUntil = now + timerDur.seconds)
        timerUntil != null -> copy(timerUntil = null, timerLeft = ((timerLeftMs(now) + 500) / 1000).toInt())
        else -> {
            val left = if (timerLeft > 0) timerLeft else timerDur
            copy(timerLeft = left, timerUntil = now + left.seconds)
        }
    }

    fun timerShift(sec: Int, now: Instant): Tools = when {
        timerUntil == null -> copy(timerLeft = (timerLeft + sec).coerceAtLeast(0))
        sec > 0 -> copy(timerUntil = maxOf(timerUntil, now) + sec.seconds)
        else -> copy(timerUntil = timerUntil + sec.seconds)
    }

    fun stopwatchMs(now: Instant): Long = swAcc + (swStart?.let { (now - it).inWholeMilliseconds } ?: 0)

    fun stopwatchToggle(now: Instant): Tools =
        if (swStart != null) copy(swAcc = stopwatchMs(now), swStart = null) else copy(swStart = now)

    /** Обнулить и остановить. */
    fun stopwatchReset(): Tools = copy(swAcc = 0, swStart = null)
}
