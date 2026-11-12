package train.server

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.QueryParam
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import train.core.API_PREFIX
import train.core.BODYWEIGHT
import train.core.Stats
import train.core.WeekSummary
import train.core.monday
import train.core.stats
import train.core.weeks

/**
 * Сводка за период для раздела «Замеры»: нагрузка по мышцам и участкам тела, минуты по дням,
 * объём. Считает [stats] из core — так же, как посчитал бы телефон, будь у него все подходы.
 */
@Path("$API_PREFIX/stats")
class StatsResource(
    private val db: Db,
    private val user: CurrentUser,
    private val store: Store,
    private val days: Days,
) {
    /** `from`, `to` — по дню тренировки, включительно; без них — последние 30 дней. */
    @GET
    fun get(@QueryParam("from") from: String?, @QueryParam("to") to: String?): Stats {
        val end = parseDate(to) ?: days.today()
        val start = parseDate(from) ?: end.minus(DatePeriod(days = 29))
        if (start > end) throw badRequest("from позже to")
        val workouts = db.list(
            "select * from workout where user_id = ? and not deleted and start_date >= ? and start_date <= ?",
            user.id, start, end,
        ) { it.workout() }
        val sets = if (workouts.isEmpty()) emptyList()
        else db.list(
            "select * from workout_set where workout_id = any(?) and done and not deleted",
            UuidList(workouts.map { it.id }),
        ) { it.setFact() }
        // Вес тела для объёма: последнее взвешивание до старта или на самой тренировке (bodyweightFor)
        val weights = db.list(
            "select * from measurement where user_id = ? and type = ? and not deleted and measured_at < ? order by measured_at",
            user.id, BODYWEIGHT, days.end(end),
        ) { it.measurement() }
        return stats(start, end, workouts, sets, store.exercises(user.id, sets.map { it.exerciseId }.toSet()), weights, days.zone)
    }

    /**
     * Недели с понедельника для агента ([weeks]), старые сначала. `from`, `to` — дни внутри первой
     * и последней недели; без них — восемь недель по текущую.
     */
    @GET
    @Path("weeks")
    fun weeks(@QueryParam("from") from: String?, @QueryParam("to") to: String?): List<WeekSummary> {
        val last = parseDate(to) ?: days.today()
        val first = (parseDate(from) ?: last.minus(DatePeriod(days = 7 * 7))).monday()
        if (first > last) throw badRequest("from позже to")
        if (first < last.minus(DatePeriod(days = 7 * 104))) throw badRequest("не больше двух лет")
        val end = last.monday().plus(DatePeriod(days = 6))
        val workouts = db.list(
            "select * from workout where user_id = ? and not deleted and start_date >= ? and start_date <= ?",
            user.id, first, end,
        ) { it.workout() }
        val sets = if (workouts.isEmpty()) emptyList()
        else db.list(
            "select * from workout_set where workout_id = any(?) and not deleted",
            UuidList(workouts.map { it.id }),
        ) { it.setFact() }
        val weights = db.list(
            "select * from measurement where user_id = ? and type = ? and not deleted and measured_at < ? order by measured_at",
            user.id, BODYWEIGHT, days.end(end),
        ) { it.measurement() }
        return weeks(first, last, workouts, sets, store.exercises(user.id, sets.map { it.exerciseId }.toSet()), weights, days.zone)
    }
}
