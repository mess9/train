package train.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import train.core.Photo
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Фото прогресса: камера и галерея телефона, лист перед загрузкой, сетка и просмотр

val poses = listOf("front" to "Спереди", "side" to "Сбоку", "back" to "Сзади")

fun poseLabel(pose: String?): String? = pose?.let { p -> poses.firstOrNull { it.first == p }?.second ?: p }

/** Полдень того дня по часам телефона: фото задним числом без времени. */
fun LocalDate.noon(): Instant = atTime(12, 0).toInstant(TimeZone.currentSystemDefault())

/** Снять или выбрать фото. [workoutId] — фото этой тренировки. */
@Composable
fun PhotoButtons(app: AppState, workoutId: Uuid?) {
    val p = app.progress
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoBtn(Icon.Camera, "Камера", Modifier.weight(1f)) { p.pick(camera = true, workoutId) }
        PhotoBtn(Icon.Image, "Из галереи", Modifier.weight(1f)) { p.pick(camera = false, workoutId) }
    }
    when (p.picking) {
        Picking.Waiting -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("Жду фото…", T.meta, Modifier.weight(1f))
            Txt("Отмена", ts(13.sp, C.Muted, FontWeight.SemiBold), Modifier.clip(RoundedCornerShape(8.dp)).tap(p::stopPicking).padding(6.dp))
        }
        Picking.Compressing -> Txt("Сжимаю фото…", T.meta, Modifier.padding(top = 8.dp))
        Picking.None -> p.pickError?.let { Txt(it, ts(13.sp, C.Danger), Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun PhotoBtn(icon: Icon, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.Field).tap(onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ico(icon, 20.dp, C.Lime)
        Txt(label, ts(14.sp, C.Text, FontWeight.SemiBold))
    }
}

/** Миниатюры в сетку по 3; тап — на весь экран. */
@Composable
fun PhotoGrid(app: AppState, photos: List<Photo>, columns: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { PhotoTile(app, it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PhotoTile(app: AppState, photo: Photo, modifier: Modifier) {
    LaunchedEffect(photo.id) { app.progress.thumb(photo.id) }
    val img = app.progress.thumbs[photo.id]
    Box(modifier.aspectRatio(0.8f).clip(RoundedCornerShape(10.dp)).background(C.Field).tap { app.progress.open(photo.id) }) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Ico(Icon.Image, 24.dp, C.Faint) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            Txt(photo.takenAt.date().dayMonth() + (poseLabel(photo.pose)?.let { " · " + it.lowercase() } ?: ""), ts(10.sp, C.Soft), maxLines = 1)
        }
    }
}

/** Поверх любого экрана: лист нового фото и просмотр открытого. */
@Composable
fun PhotoOverlays(app: AppState) {
    val p = app.progress
    p.draft?.let { DraftSheet(app, it) }
    p.opened?.let { (id, img) -> Viewer(app, p.photos.firstOrNull { it.id == id }, id, img) }
}

@Composable
private fun DraftSheet(app: AppState, d: PhotoDraft) {
    val p = app.progress
    val shot = d.takenAt.date()
    val today = app.today()
    var day by remember(d.id) { mutableStateOf(minOf(shot, today)) }
    var pose by remember(d.id) { mutableStateOf<String?>(null) }
    var picking by remember(d.id) { mutableStateOf(false) }
    if (picking) LaunchedEffect(Unit) {
        // Системный выбор даты отвечает строкой в bridge: опрашиваем, пока лист открыт
        repeat(600) {
            val s = takeDate()
            if (s.isNotEmpty()) {
                runCatching { LocalDate.parse(s) }.getOrNull()?.let { day = minOf(it, today) }
                picking = false
                return@LaunchedEffect
            }
            delay(200)
        }
        picking = false
    }
    Sheet({ if (!p.uploading) p.discard() }) {
        Txt(if (d.workoutId != null) "Фото к тренировке" else "Фото прогресса", T.strong)
        Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg), contentAlignment = Alignment.Center) {
            if (d.preview != null) Image(d.preview, null, Modifier.fillMaxWidth().heightIn(max = 300.dp), contentScale = ContentScale.Fit)
            else Txt("Превью нет — браузер не показывает этот формат, но сервер фото примет", T.meta, Modifier.padding(20.dp))
        }
        Txt("Когда снято", ts(13.sp, C.Muted, FontWeight.Medium))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Icon.Back, { day = day.minus(DatePeriod(days = 1)) }, 40.dp)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Field)
                    .tap { picking = true; pickDate(day.toString(), today.toString()) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(Icon.Calendar, 18.dp, C.Muted)
                Txt(if (day == today) "Сегодня" else day.short(), ts(15.sp, weight = FontWeight.SemiBold))
            }
            IconBtn(Icon.Next, { if (day < today) day = day.plus(DatePeriod(days = 1)) }, 40.dp, if (day < today) C.Soft else C.Dash)
        }
        Txt("Ракурс", ts(13.sp, C.Muted, FontWeight.Medium))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            poses.forEach { (v, label) ->
                Btn(label, { pose = if (pose == v) null else v }, Modifier.weight(1f), if (pose == v) Kind.ChipOn else Kind.Chip)
            }
        }
        p.uploadError?.let { Txt(it, ts(13.sp, if (p.waitingForNetwork) C.Normal else C.Danger)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Не сохранять", p::discard, Modifier.weight(1f), Kind.Ghost, enabled = !p.uploading)
            Btn(
                when {
                    p.uploading -> "Загружаю…"
                    p.uploadError != null -> "Ещё раз"
                    else -> "Сохранить"
                },
                // Тот же день, что у файла, — точное время снимка; другой день — полдень
                { p.upload(if (day == shot) d.takenAt else day.noon(), pose) },
                Modifier.weight(1f), enabled = !p.uploading,
            )
        }
    }
}

@Composable
private fun Viewer(app: AppState, photo: Photo?, id: Uuid, img: androidx.compose.ui.graphics.ImageBitmap?) {
    val p = app.progress
    var confirm by remember(id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black).tapQuiet {}) {
        if (img != null) Image(img, null, Modifier.fillMaxSize().padding(top = 64.dp, bottom = 96.dp), contentScale = ContentScale.Fit)
        else Txt("Загружаю…", T.meta, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                photo?.let {
                    Txt(it.takenAt.date().long(), T.strong)
                    val sub = listOfNotNull(poseLabel(it.pose), if (it.workoutId != null) "с тренировки" else null).joinToString(" · ")
                    if (sub.isNotEmpty()) Txt(sub, T.meta)
                }
            }
            IconBtn(Icon.Close, p::closePhoto)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.deleteError?.let { Txt(it, ts(13.sp, C.Danger)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirm) {
                    Btn("Оставить", { confirm = false }, Modifier.weight(1f), Kind.Ghost)
                    Btn("Удалить", { p.delete(id) }, Modifier.weight(1f))
                } else {
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(C.Field).tap { confirm = true }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Ico(Icon.Trash, 18.dp, C.Danger)
                        Txt("В корзину", ts(14.sp, C.Danger, FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}
