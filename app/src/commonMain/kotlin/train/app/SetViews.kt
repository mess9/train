package train.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.core.Rating
import train.core.RecordType
import train.core.SetValues
import train.core.TimeInput

// Строка подхода колонками «кг / повт» и точка оценки справа — общая для тренировки и истории

/** Ширина колонки значения: поле ввода и ячейка выполненного подхода совпадают. */
val CellW = 50.dp

/** Ширина колонки номера подхода и колонки «было». */
val NoW = 24.dp
val PrevW = 32.dp

/** Колонки подхода по типу записи: (значение, единица). Пустое значение — «—». */
fun cells(type: RecordType, v: SetValues): List<Pair<String, String>> {
    fun n(x: Double?) = x?.let(::num) ?: "—"
    return when (type) {
        RecordType.WEIGHT_REPS -> listOf(n(v.weight) to "кг", (v.reps?.toString() ?: "—") to "повт")
        RecordType.REPS -> listOfNotNull((v.reps?.toString() ?: "—") to "повт", v.extraWeight?.let { "+" + num(it) to "кг" })
        RecordType.TIME -> listOf((v.seconds?.let(TimeInput::clock) ?: "—") to "мин")
        RecordType.WEIGHT_TIME -> listOf(n(v.weight) to "кг", (v.seconds?.let(TimeInput::clock) ?: "—") to "мин")
    }
}

/** Ячейка значения с единицей мелко под ним. `done` — выполнено: серым и зачёркнуто. */
@Composable
fun Cell(value: String, unit: String, done: Boolean, color: Color = C.Text) {
    Column(Modifier.width(CellW), horizontalAlignment = Alignment.CenterHorizontally) {
        // Зачёркивание рисуем сами: TextDecoration.LineThrough на canvas не виден
        Txt(
            value,
            ts(15.sp, if (done) C.Done else color, FontWeight.SemiBold),
            if (done) Modifier.drawWithContent {
                drawContent()
                val y = size.height * 0.55f
                drawLine(C.Done, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
            } else Modifier,
            align = TextAlign.Center,
            maxLines = 1,
        )
        Txt(unit, ts(9.sp, C.Faint, FontWeight.Medium).copy(lineHeight = 10.sp), align = TextAlign.Center)
    }
}

/** Номер подхода и подпись под ним мелко: время отметки, «из N», «доп». */
@Composable
fun SetNo(no: Int, sub: String?, color: Color = C.Text) {
    Column(Modifier.width(NoW), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(no.toString(), ts(14.sp, color, FontWeight.Bold))
        if (sub != null) Txt(sub, ts(9.sp, C.Faint).copy(lineHeight = 10.sp), maxLines = 1)
    }
}

/**
 * Выполненный подход одной строкой: № · было · значения колонками · оценка точкой справа.
 * `done` — серым и зачёркнуто, как в тренировке; в истории — обычным цветом.
 */
@Composable
fun DoneSetLine(
    no: Int,
    sub: String?,
    type: RecordType,
    values: SetValues,
    rating: Rating?,
    modifier: Modifier = Modifier,
    prev: String? = null,
    done: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SetNo(no, sub, if (done) C.Done else C.Text)
        if (prev != null) Txt(prev, ts(11.sp, C.Faint), Modifier.width(PrevW), maxLines = 2)
        cells(type, values).forEach { (v, u) -> Cell(v, u, done) }
        Box(Modifier.weight(1f))
        trailing()
        if (rating != null) Txt(rating.label().lowercase(), ts(11.sp, if (done) C.Done else C.Muted))
        RateDot(rating)
        Box(Modifier.width(6.dp))
    }
}
