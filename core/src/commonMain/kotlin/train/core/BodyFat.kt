package train.core

import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** Складки калипером, мм — точки формулы Джексона–Поллока для мужчин, правая сторона. */
const val FOLD_CHEST = "fold_chest"
const val FOLD_ABDOMEN = "fold_abdomen"
const val FOLD_THIGH = "fold_thigh"
val FOLDS = listOf(FOLD_CHEST, FOLD_ABDOMEN, FOLD_THIGH)

/** Процент жира по складкам ([bodyFatJp3]), пишет телефон вместе со складками. */
const val BODYFAT = "bodyfat"

/** Процент жира на глаз по фото прогресса — оценка агента, `--note` не нужен: метод в типе. */
const val BODYFAT_PHOTO = "bodyfat_photo"

/**
 * Процент жира по трём складкам (грудь, живот, бедро, мм): плотность по Джексону–Поллоку для
 * мужчин, процент по Сири. Абсолют плавает на ±3–4 %, смысл — в тренде при одной технике.
 */
fun bodyFatJp3(chest: Double, abdomen: Double, thigh: Double, age: Int): Double {
    val s = chest + abdomen + thigh
    val density = 1.10938 - 0.0008267 * s + 0.0000016 * s * s - 0.0002574 * age
    return ((495 / density - 450) * 10).roundToInt() / 10.0
}

/** Полных лет на дату [on]. */
fun ageAt(birth: LocalDate, on: LocalDate): Int =
    on.year - birth.year - if (on.month < birth.month || (on.month == birth.month && on.day < birth.day)) 1 else 0
