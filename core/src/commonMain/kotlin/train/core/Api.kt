package train.core

import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Ответ API с ошибкой. `409` вместо этого отдаёт текущее состояние записи. */
@Serializable
data class ApiError(val error: String)

/**
 * Владелец ключа: приложение привязывает к нему очередь (ADR 0008). [admin] заводит
 * пользователей и ведёт общий справочник упражнений (ADR 0011).
 */
@Serializable
data class Me(val userId: Uuid, val email: String, val name: String? = null, val admin: Boolean = false)

/** Новый пользователь — например, друг со своим телефоном. */
@Serializable
data class UserCreate(val email: String, val name: String? = null, val keyLabel: String? = null)

@Serializable
data class User(val id: Uuid, val email: String, val name: String? = null, val admin: Boolean = false, val createdAt: Instant)

/** Пользователь и его первый ключ: ключ показывается один раз. */
@Serializable
data class UserCreated(val user: User, val key: KeyCreated)

@Serializable
data class KeyCreate(val label: String? = null)

/** Ключ показывается один раз, на сервере остаётся только хеш. */
@Serializable
data class KeyCreated(val id: Uuid, val key: String, val label: String? = null)

/** Ключ в списке — без самого ключа. `current` — тот, которым сделан запрос. */
@Serializable
data class ApiKey(
    val id: Uuid,
    val label: String? = null,
    val createdAt: Instant,
    val revokedAt: Instant? = null,
    val current: Boolean = false,
)

/** Упражнение из источника (exercises-dataset); в справочник добавляется через [ExerciseCreate]. */
@Serializable
data class SourceExercise(
    val id: String,
    val name: String,
    val bodyPart: String? = null,
    val target: String? = null,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: String? = null,
    val mediaKey: String? = null,
)

/** Добавить упражнение в справочник: из источника (`sourceId`) или своё. */
@Serializable
data class ExerciseCreate(
    val nameRu: String,
    val sourceId: String? = null,
    val nameEn: String? = null,
    val recordType: RecordType? = null,
    val implements: Int? = null,
    val perSide: Boolean? = null,
    val bodyweightFactor: Double? = null,
    val equipment: String? = null,
    val musclesPrimary: List<String>? = null,
    val musclesSecondary: List<String>? = null,
    /**
     * В общий справочник (видят все) или только себе. `null` — у админа в общий, у остальных
     * себе. В общий добавляет только админ (ADR 0011).
     */
    val shared: Boolean? = null,
)

/** Правка справочника: меняются только переданные поля. */
@Serializable
data class ExercisePatch(
    val nameRu: String? = null,
    val nameEn: String? = null,
    val recordType: RecordType? = null,
    val implements: Int? = null,
    val perSide: Boolean? = null,
    val bodyweightFactor: Double? = null,
    val equipment: String? = null,
    val musclesPrimary: List<String>? = null,
    val musclesSecondary: List<String>? = null,
    /** Шаги техники; пустой список — убрать. */
    val instructions: List<String>? = null,
    /**
     * Привязать к упражнению источника: из него берутся картинка и, если своих шагов нет и они
     * не переданы, шаги. Пустая строка — отвязать (картинка уходит, шаги остаются).
     */
    val sourceId: String? = null,
)

/**
 * Тренировка вместе с подходами, включая удалённые: они прячут строки плана.
 * [measurements] — замеры, привязанные к тренировке.
 */
@Serializable
data class WorkoutDetail(val workout: Workout, val sets: List<SetFact>, val measurements: List<Measurement> = emptyList())

@Serializable
data class Trash(
    val plans: List<Plan> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
)

@Serializable
data class Purged(val plans: Int, val workouts: Int, val measurements: Int, val photos: Int = 0, val journal: Int = 0)

/** Типы фото, которые принимает сервер; тип определяется по содержимому, не по заголовку. */
val PHOTO_TYPES = listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

/**
 * Фото прогресса (ADR 0010). Файл — `GET /api/photos/{id}/file`. [takenAt] — когда снято, можно
 * задним числом; [pose] — ракурс: `front`, `side`, `back` или свой.
 */
@Serializable
data class Photo(
    val id: Uuid,
    val takenAt: Instant,
    val contentType: String,
    val size: Long,
    /** SHA-256 файла, hex: повторная загрузка того же файла под тем же id — не ошибка. */
    val sha256: String,
    val pose: String? = null,
    val note: String? = null,
    val workoutId: Uuid? = null,
    val createdAt: Instant,
    /** Не `null` — фото в корзине. */
    val deletedAt: Instant? = null,
)

/** Правка фото: меняются только переданные поля. Пустая строка убирает ракурс или заметку. */
@Serializable
data class PhotoPatch(
    val takenAt: Instant? = null,
    val pose: String? = null,
    val note: String? = null,
    val workoutId: Uuid? = null,
)
