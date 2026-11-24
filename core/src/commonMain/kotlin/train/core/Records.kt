package train.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Рекорд упражнения в одних единицах [unit]: секунды и кг × с, повторы и оценка Эпли не сравниваются. `null` — кэш старого сервера. */
@Serializable
data class ExerciseBest(
    val exerciseId: Uuid,
    val score: Double,
    val set: DoneSet,
    val date: LocalDate,
    val workoutId: Uuid,
    val unit: ScoreUnit? = null,
)

@Serializable
data class VolumeBest(val volumeKg: Double, val date: LocalDate, val workoutId: Uuid)

/** Планка из истории до текущей тренировки; пустая — прошлое неизвестно или упражнение новое. */
@Serializable
data class Records(val exercises: List<ExerciseBest> = emptyList(), val volume: VolumeBest? = null) {
    /** Кэш мог уже включить текущую тренировку: такой результат не годится в качестве прошлого. */
    fun without(workoutId: Uuid): Records = copy(
        exercises = exercises.filterNot { it.workoutId == workoutId },
        volume = volume?.takeUnless { it.workoutId == workoutId },
    )
}

/** Сервер ещё не видит всю офлайн-очередь: её сохранённые рекорды нельзя понизить ответом сети. */
fun Records.withPending(local: Records, workoutIds: Set<Uuid>, exclude: Uuid? = null): Records {
    val exercises = (exercises + local.exercises.filter { it.workoutId in workoutIds })
        .filter { it.workoutId != exclude }.groupBy { it.exerciseId to it.unit }.values.map { candidates -> candidates.maxBy { it.score } }
    val volume = listOfNotNull(volume, local.volume?.takeIf { it.workoutId in workoutIds })
        .filter { it.workoutId != exclude }.maxByOrNull { it.volumeKg }
    return Records(exercises, volume)
}

/**
 * Завершённая офлайн-тренировка становится прошлым для следующей ещё до синхронизации.
 * Первые выполнения задают планку тоже, хотя сами кубка не получают.
 */
fun Records.withCompletedWorkout(
    workout: Workout,
    sets: List<SetFact>,
    exercises: Map<Uuid, Exercise>,
    measurements: List<Measurement> = emptyList(),
    zone: TimeZone = TimeZone.UTC,
): Records {
    if (workout.active || workout.deleted) return this
    val completed = records(listOf(workout), sets, exercises, measurements, zone)
    val best = this.exercises.associateBy { it.exerciseId to it.unit }.toMutableMap()
    completed.exercises.forEach { candidate ->
        val key = candidate.exerciseId to candidate.unit
        val old = best[key]
        if (old == null || improvesRecord(candidate.score, old.score)) best[key] = candidate
    }
    val volume = completed.volume?.takeIf { this.volume == null || it.volumeKg > this.volume.volumeKg } ?: this.volume
    return Records(best.values.toList(), volume)
}

/**
 * Рекорды всей истории, включая импорт, — по одному на упражнение и единицы оценки. Вес тела —
 * как в [history]. Для разбора старой тренировки [before] ограничивает прошлое.
 */
fun records(
    workouts: List<Workout>,
    sets: List<SetFact>,
    exercises: Map<Uuid, Exercise>,
    measurements: List<Measurement> = emptyList(),
    zone: TimeZone = TimeZone.UTC,
    ids: Collection<Uuid>? = null,
    exclude: Uuid? = null,
    before: Instant? = null,
): Records {
    val past = workouts.filter { !it.deleted && it.id != exclude && (before == null || it.startedAt < before) }
    val byId = past.associateBy { it.id }
    val wanted = ids?.toSet()
    val firstWeight = measurements.filter { it.type == BODYWEIGHT && !it.deleted }.minByOrNull { it.measuredAt }?.value
    val bodyweight = past.associate { it.id to (bodyweightFor(it, measurements) ?: firstWeight) }
    val best = sets.filter { it.done && !it.deleted && it.workoutId in byId && (wanted == null || it.exerciseId in wanted) }
        .sortedWith(compareBy({ byId.getValue(it.workoutId).startedAt }, { it.doneAt }, { it.setNo }))
        .mapNotNull { f ->
            val set = f.toDone(f.exerciseId)
            scoredSet(set, exercises[f.exerciseId]?.bodyweightFactor, bodyweight[f.workoutId])
                ?.takeIf { it.value.isFinite() }?.let { Triple(f, set, it) }
        }
        .groupBy { (f, _, s) -> f.exerciseId to s.unit }
        .map { (_, scored) ->
            // Внутри тренировки — просто лучший подход, допуск — между тренировками.
            scored.groupBy { it.first.workoutId }.values.map { w -> w.maxBy { it.third.value } }
                .fold(null as Triple<SetFact, DoneSet, ScoredSet>?) { b, x ->
                    if (b == null || improvesRecord(x.third.value, b.third.value)) x else b
                }!!
        }
        .map { (f, set, s) ->
            val w = byId.getValue(f.workoutId)
            ExerciseBest(f.exerciseId, s.value, set, w.date ?: w.localDate(zone), w.id, s.unit)
        }
    val volume = workoutVolumes(past, sets, exercises, measurements).maxByOrNull { it.volumeKg }?.let { v ->
        val w = byId.getValue(v.workoutId)
        VolumeBest(v.volumeKg, w.date ?: w.localDate(zone), w.id)
    }
    return Records(best, volume)
}

/**
 * Один кубок на упражнение — у лучшего сделанного подхода тренировки в единицах прошлого рекорда.
 * Допуск применяется к прошлой истории, а не между подходами этой тренировки. При равенстве
 * остаётся первый. Значение — какой исторический рекорд побит; нет прошлого в тех же единицах —
 * нет кубка. Рекорд без единиц (старый кэш) сравнивается с любыми, как раньше.
 */
fun setRecords(sets: List<SetFact>, exercises: Map<Uuid, Exercise>, bodyweight: Double?, best: Records): Map<Uuid, ExerciseBest> {
    val previous = best.exercises.associateBy { it.exerciseId to it.unit }
    return sets.filter { it.done && !it.deleted }.groupBy { it.exerciseId }.mapNotNull { (exerciseId, facts) ->
        facts.sortedWith(compareBy({ it.doneAt }, { it.setNo }, { it.id.toString() })).mapNotNull { f ->
            scoredSet(f.toDone(exerciseId), exercises[exerciseId]?.bodyweightFactor, bodyweight)?.takeIf { it.value.isFinite() }?.let { f to it }
        }.groupBy { it.second.unit }.entries.firstNotNullOfOrNull { (unit, scored) ->
            val old = previous[exerciseId to unit] ?: previous[exerciseId to null] ?: return@firstNotNullOfOrNull null
            val winner = scored.maxBy { it.second.value }
            if (improvesRecord(winner.second.value, old.score)) winner.first.id to old else null
        }
    }.toMap()
}

/** Неизвестная часть объёма не мешает сравнить известную; без прошлой тренировки рекорда нет. */
fun volumeRecord(volumeKg: Double, best: Records): VolumeBest? = best.volume?.takeIf { volumeKg > it.volumeKg }
