package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.client.Link
import train.core.BODYWEIGHT
import train.core.ItemComparison
import train.core.Measurement
import train.core.RecordType
import train.core.SetValues
import train.core.close
import train.core.comment
import train.core.bodyweightFor
import train.core.compare
import train.core.measure
import train.core.newId
import train.core.workoutForMeasurement
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@Composable
fun Technique(app: AppState, exerciseId: Uuid) {
    val ex = app.exercise(exerciseId)
    Page {
        BackLink(if (app.session?.active != null) "К тренировке" else "Назад", app::back)
        Txt(ex?.nameRu ?: "Упражнение не в справочнике", T.h1, Modifier.padding(bottom = 14.dp))
        val key = ex?.mediaKey
        if (key != null) {
            Gif(app, key, Modifier.fillMaxWidth().aspectRatio(1f))
        } else {
            Column(
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).background(C.Card),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ico(Icon.Dumbbell, 36.dp, C.Muted, 1.6f)
                Txt("Медиа для этого упражнения нет", T.meta)
            }
        }
        if (ex == null) return@Page
        Section("Мышцы")
        val muscles = ex.musclesPrimary.joinToString(", ", transform = ::muscle).ifEmpty { "не указаны" } +
            (ex.musclesSecondary.takeIf { it.isNotEmpty() }?.let { " · вспомогательные: " + it.joinToString(", ", transform = ::muscle) } ?: "")
        Txt(muscles, ts(15.sp, C.Soft))
        Section("Как делать")
        if (ex.instructions.isEmpty()) Txt("Описания нет", T.meta)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ex.instructions.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("${i + 1}.", ts(15.sp, C.Muted, FontWeight.SemiBold), Modifier.width(22.dp))
                    Txt(step, ts(15.sp, C.Soft), Modifier.weight(1f))
                }
            }
        }
    }
}

