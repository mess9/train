package train.server

import jakarta.transaction.Transactional
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import train.core.API_PREFIX
import train.core.HealthExcluded
import train.core.HealthExclusion
import train.core.HealthExclusionCreate
import train.core.HealthRecord
import train.core.HealthReport
import train.core.HealthUpload
import train.core.HealthUploaded
import train.core.covers
import train.core.healthDays
import train.core.newId

/** Больше записей за раз не принимаем: телефон шлёт пачками. */
const val HEALTH_UPLOAD_MAX = 5000

/** Отчёт без дат — за столько последних дней. */
const val HEALTH_DEFAULT_DAYS = 14

const val HEALTH_MAX_DAYS = 366

/**
 * Сон, пульс, шаги с телефона (ADR 0015). Записи Health Connect хранятся как есть, ночи и дни
 * считает core при чтении. Телефон — единственный писатель: повтор записи с тем же id заменяет
 * её, протокол синхронизации тренировок (ADR 0007) здесь не нужен.
 */
@Path("$API_PREFIX/health")
class HealthResource(private val db: Db, private val user: CurrentUser, private val days: Days) {
    /**
     * Новые и изменённые записи и id удалённых в источнике. Записи дней из исключений не
     * сохраняются. Повтор пачки ничего не меняет.
     */
    @POST
    @Path("records")
    @Transactional
    fun upload(body: HealthUpload): HealthUploaded {
        if (body.records.size > HEALTH_UPLOAD_MAX) throw badRequest("больше $HEALTH_UPLOAD_MAX записей за раз")
        body.records.forEach(::check)
        val exclusions = exclusions()
        // Последняя версия записи в пачке; ON CONFLICT не обновляет строку дважды за запрос
        val (excluded, keep) = body.records.associateBy { it.id }.values
            .partition { r -> exclusions.any { it.covers(r, days.zone) } }
        if (keep.isNotEmpty()) db.update(
            "insert into health_record (user_id, id, type, source, start_at, end_at, data) " +
                "select ?, r->>'id', r->>'type', r->>'source', cast(r->>'start' as timestamptz), cast(r->>'end' as timestamptz), r " +
                "from jsonb_array_elements(cast(? as jsonb)) r " +
                "on conflict (user_id, id) do update set type = excluded.type, source = excluded.source, " +
                "start_at = excluded.start_at, end_at = excluded.end_at, data = excluded.data, received_at = now()",
            user.id, Json.of(ListSerializer(HealthRecord.serializer()), keep),
        )
        val deleted = if (body.deleted.isEmpty()) 0 else db.update(
            "delete from health_record where user_id = ? and id in (select jsonb_array_elements_text(cast(? as jsonb)))",
            user.id, Json.of(ListSerializer(String.serializer()), body.deleted.distinct()),
        )
        return HealthUploaded(saved = keep.size, deleted = deleted, excluded = excluded.size)
    }

    /** Дни `from`..`to` подряд; без дат — последние [HEALTH_DEFAULT_DAYS] дней. */
    @GET
    fun report(@QueryParam("from") from: String?, @QueryParam("to") to: String?): HealthReport {
        val end = parseDate(to) ?: days.today()
        val start = parseDate(from) ?: end.minus(DatePeriod(days = HEALTH_DEFAULT_DAYS - 1))
        if (start > end) throw badRequest("from позже to")
        if (start.daysUntil(end) >= HEALTH_MAX_DAYS) throw badRequest("не больше $HEALTH_MAX_DAYS дней за раз")
        // Ночь дня начинается накануне вечером; у записей свой пояс — берём с запасом в сутки
        val records = records(start.minus(DatePeriod(days = 1)), end.plus(DatePeriod(days = 1)))
        val exclusions = exclusions()
        return HealthReport(
            from = start,
            to = end,
            days = healthDays(records, start, end, days.zone, exclusions),
            exclusions = exclusions.filter { it.to >= start && it.from <= end },
            sources = records.map { it.source }.distinct().sorted(),
        )
    }

    @GET
    @Path("exclusions")
    fun listExclusions(): List<HealthExclusion> = exclusions()

    /** Исключить дни: уже сохранённые записи этих дней удаляются, новые не принимаются. */
    @POST
    @Path("exclusions")
    @Transactional
    fun exclude(body: HealthExclusionCreate): HealthExcluded {
        if (body.from > body.to) throw badRequest("from позже to")
        val e = HealthExclusion(newId(), body.from, body.to, body.source?.trim()?.ifEmpty { null }, body.note?.trim()?.ifEmpty { null })
        db.update(
            "insert into health_exclusion (id, user_id, from_date, to_date, source, note) values (?, ?, ?, ?, ?, ?)",
            e.id, user.id, e.from, e.to, e.source, e.note,
        )
        val ids = records(e.from.minus(DatePeriod(days = 1)), e.to.plus(DatePeriod(days = 1)))
            .filter { e.covers(it, days.zone) }.map { it.id }
        val removed = if (ids.isEmpty()) 0 else db.update(
            "delete from health_record where user_id = ? and id in (select jsonb_array_elements_text(cast(? as jsonb)))",
            user.id, Json.of(ListSerializer(String.serializer()), ids),
        )
        return HealthExcluded(e, removed)
    }

    /** Снять исключение; удалённые записи вернёт только повторная отправка с телефона. */
    @DELETE
    @Path("exclusions/{id}")
    @Transactional
    fun unexclude(@PathParam("id") id: String) {
        if (db.update("delete from health_exclusion where id = ? and user_id = ?", parseId(id), user.id) == 0) throw notFound()
    }

    /** Записи, начавшиеся в днях `from`..`to` по поясу сервера. */
    private fun records(from: LocalDate, to: LocalDate): List<HealthRecord> = db.list(
        "select data from health_record where user_id = ? and start_at >= ? and start_at < ? order by start_at",
        user.id, days.start(from), days.end(to),
    ) { it.json("data", HealthRecord.serializer()) }

    private fun exclusions(): List<HealthExclusion> = db.list(
        "select * from health_exclusion where user_id = ? order by from_date", user.id,
    ) {
        HealthExclusion(it.uuidReq("id"), it.date("from_date")!!, it.date("to_date")!!, it.string("source"), it.string("note"))
    }

    private fun check(r: HealthRecord) {
        val what = "запись ${r.id.take(80)}"
        if (r.id.isBlank() || r.id.length > 200) throw badRequest("id записи — от 1 до 200 символов")
        if (r.source.isBlank() || r.source.length > 200) throw badRequest("$what: source — от 1 до 200 символов")
        r.end?.let { if (it < r.start) throw badRequest("$what: конец раньше начала") }
        if (r.value?.isFinite() == false) throw badRequest("$what: value не число")
        if (r.hrSec.orEmpty().size != r.hrBpm.orEmpty().size) throw badRequest("$what: hrSec и hrBpm разной длины")
        r.stages?.forEach { if (it.end < it.start) throw badRequest("$what: фаза сна кончается раньше начала") }
        r.zoneOffset?.let { if (it !in -18 * 3600..18 * 3600) throw badRequest("$what: zoneOffset вне ±18 ч") }
    }
}
