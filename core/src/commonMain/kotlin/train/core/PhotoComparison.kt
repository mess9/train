package train.core

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

data class PhotoGroup(val pose: String?, val photos: List<Photo>)

/** Новые ракурсы сначала; корзина и группы с одним снимком не участвуют. */
fun comparisonGroups(photos: List<Photo>): List<PhotoGroup> = photos.filter { it.deletedAt == null }
    .sortedWith(compareByDescending<Photo> { it.takenAt }.thenBy { it.id.toString() })
    .groupBy { it.pose?.trim()?.takeIf(String::isNotEmpty) }
    .mapNotNull { (pose, shots) -> shots.takeIf { it.size >= 2 }?.let { PhotoGroup(pose, it) } }

/** Точный день необязателен: при равном расстоянии выбираем более ранний снимок. */
fun nearestPhoto(photos: List<Photo>, day: LocalDate, zone: TimeZone): Photo? = photos.filter { it.deletedAt == null }
    .minWithOrNull(compareBy<Photo> { abs(it.takenAt.toLocalDateTime(zone).date.daysUntil(day)) }
        .thenBy { it.takenAt }.thenBy { it.id.toString() })

/** Насколько снимок быстрого выбора может отстоять от целевого дня: иначе «3 мес» взял бы недельный. */
const val PHOTO_PRESET_SLACK_DAYS = 15

/**
 * Календарные месяцы с ограничением конца месяца; быстрый выбор всегда строго раньше «После» и
 * не дальше [PHOTO_PRESET_SLACK_DAYS] от цели. Нет такого — `null`, кнопка неактивна.
 */
fun photoMonthsBefore(photos: List<Photo>, after: Photo, months: Int, zone: TimeZone): Photo? {
    require(months > 0)
    val target = after.takenAt.toLocalDateTime(zone).date.minus(DatePeriod(months = months))
    return nearestPhoto(photos.filter {
        it.id != after.id && it.takenAt < after.takenAt &&
            abs(it.takenAt.toLocalDateTime(zone).date.daysUntil(target)) <= PHOTO_PRESET_SLACK_DAYS
    }, target, zone)
}

/** Вес — ближайший по времени, в пределах семи календарных дней телефона; при равенстве раньше. */
fun photoWeight(photo: Photo, measurements: List<Measurement>, zone: TimeZone): Measurement? {
    val day = photo.takenAt.toLocalDateTime(zone).date
    return measurements.filter {
        !it.deleted && it.type == BODYWEIGHT && it.unit == "kg" && it.value.isFinite() && it.value > 0 &&
            abs(it.measuredAt.toLocalDateTime(zone).date.daysUntil(day)) <= 7
    }.minWithOrNull(compareBy<Measurement> { (it.measuredAt - photo.takenAt).absoluteValue }
        .thenBy { it.measuredAt }.thenBy { it.id.toString() })
}

data class PhotoInterval(val months: Int, val days: Int)

fun photoInterval(before: LocalDate, after: LocalDate): PhotoInterval {
    require(before <= after)
    var months = (after.year - before.year) * 12 + after.month.ordinal - before.month.ordinal
    if (before.plus(DatePeriod(months = months)) > after) months--
    return PhotoInterval(months, before.plus(DatePeriod(months = months)).daysUntil(after))
}

/** Сетка понедельник–воскресенье, полные недели; пустые клетки вне месяца. */
fun calendarWeeks(month: LocalDate): List<List<LocalDate?>> {
    val first = LocalDate(month.year, month.month, 1)
    val last = first.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
    val days = List<LocalDate?>(first.dayOfWeek.ordinal) { null } + (1..last.day).map { LocalDate(first.year, first.month, it) }
    return (days + List((7 - days.size % 7) % 7) { null }).chunked(7)
}
