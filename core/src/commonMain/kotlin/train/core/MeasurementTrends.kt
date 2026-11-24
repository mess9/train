package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Замеры одного типа по времени: за период, если там хотя бы две точки, иначе последние 12.
 * Флаг означает, что выбран именно период; в запасной выборке изменение считается с её начала.
 * День — в поясе телефона, как на экране замеров.
 */
fun trendPoints(
    all: List<Measurement>,
    type: String,
    from: LocalDate,
    zone: TimeZone = TimeZone.UTC,
): Pair<List<Measurement>, Boolean> {
    val points = all.filter { it.type == type && !it.deleted && it.value.isFinite() }.sortedBy { it.measuredAt }
    val inPeriod = points.filter { it.measuredAt.toLocalDateTime(zone).date >= from }
    return if (inPeriod.size >= 2) inPeriod to true else points.takeLast(12) to false
}
