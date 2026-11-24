package train.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import train.client.Link
import train.core.Photo
import train.core.PhotoInterval
import train.core.comparisonGroups
import train.core.nearestPhoto
import train.core.photoInterval
import train.core.photoMonthsBefore
import train.core.photoWeight
import kotlin.math.abs

/** Снимок открывается по тапу, подписи «До» / «После» выбирают слот ленты и календаря. */
@Composable
fun PhotoCompare(app: AppState) {
    val p = app.progress
    val zone = TimeZone.currentSystemDefault()
    LaunchedEffect(Unit) { p.loadPhotos(force = true) }
    DisposableEffect(p) { onDispose { p.comparePhotos(emptySet()) } }
    var pose by rememberSaveable { mutableStateOf<String?>(null) }
    var beforeId by rememberSaveable { mutableStateOf<String?>(null) }
    var afterId by rememberSaveable { mutableStateOf<String?>(null) }
    var activeBefore by rememberSaveable { mutableStateOf(true) }
    var preset by rememberSaveable { mutableStateOf<Int?>(1) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    val groups = comparisonGroups(p.photos)
    val group = groups.firstOrNull { (it.pose ?: "") == pose } ?: groups.firstOrNull()
    val shots = group?.photos.orEmpty()
    val after = shots.firstOrNull { it.id.toString() == afterId } ?: shots.firstOrNull()
    val before = after?.let { end ->
        shots.firstOrNull { it.id.toString() == beforeId && it.id != end.id && it.takenAt <= end.takenAt }
            ?: photoMonthsBefore(shots, end, preset ?: 1, zone)
            ?: shots.firstOrNull { it.id != end.id && it.takenAt <= end.takenAt }
    }
    LaunchedEffect(before?.id, after?.id) {
        beforeId = before?.id?.toString()
        afterId = after?.id?.toString()
        p.comparePhotos(setOfNotNull(before?.id, after?.id))
    }
    fun choose(photo: Photo) {
        if (activeBefore) beforeId = photo.id.toString() else afterId = photo.id.toString()
        preset = null
        calendar = false
    }
    val candidates = shots.filter { photo ->
        if (activeBefore) after != null && photo.id != after.id && photo.takenAt <= after.takenAt
        else before != null && photo.id != before.id && photo.takenAt >= before.takenAt
    }
    val slot = if (activeBefore) "До" else "После"
    Page {
        BackLink("К замерам", app::back)
        Header("Фото прогресса", "До / после")
        when {
            p.photos.isEmpty() && (p.photosFailed || app.link != Link.ONLINE) -> {
                Txt("Фото хранятся на сервере — покажу, когда будет связь", T.meta)
                Btn("Обновить фото", { p.loadPhotos(force = true) }, kind = Kind.Ghost, enabled = !p.photosLoading)
            }
            !p.photosLoaded -> Txt("Загружаю фото…", T.meta)
            before == null -> Txt("Для сравнения нужны два снимка одного ракурса", T.meta)
            else -> {
                if (p.photosFailed || app.link != Link.ONLINE) Txt("Показаны фото с последней загрузки — обновятся, когда будет связь", T.small, Modifier.padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(groups, key = { _, item -> item.pose ?: "" }) { _, item ->
                        Btn(poseLabel(item.pose) ?: "Без ракурса", {
                            pose = item.pose ?: ""; beforeId = null; afterId = null; preset = 1; activeBefore = true
                        }, kind = if (item.pose == group?.pose) Kind.ChipOn else Kind.Chip)
                    }
                }
                Gap(12.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..3).forEach { months ->
                        val photo = photoMonthsBefore(shots, after, months, zone)
                        Btn("$months мес", { beforeId = photo?.id?.toString(); preset = months; activeBefore = true },
                            Modifier.weight(1f).semantics {
                                role = Role.Button
                                contentDescription = "$months мес" + if (photo == null) ", недоступно" else ""
                                if (photo == null) disabled()
                            },
                            if (preset == months) Kind.ChipOn else Kind.Chip, enabled = photo != null)
                    }
                    Btn("Дата…", { calendar = true }, Modifier.weight(1f), Kind.Chip)
                }
                val beforeWeight = photoWeight(before, app.cache.measurements, zone)
                val afterWeight = photoWeight(after, app.cache.measurements, zone)
                val interval = photoInterval(before.takenAt.date(), after.takenAt.date()).text()
                val delta = if (beforeWeight != null && afterWeight != null) {
                    val difference = afterWeight.value - beforeWeight.value
                    val amount = num(abs(difference))
                    " · ${if (amount == "0") "" else if (difference < 0) "−" else "+"}$amount кг"
                } else ""
                Txt(interval + delta, T.strong, Modifier.padding(vertical = 12.dp).semantics { contentDescription = "Разница фото: $interval$delta" })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CompareSlot(app, before, "До", activeBefore, beforeWeight?.value, { activeBefore = true }, Modifier.weight(1f))
                    CompareSlot(app, after, "После", !activeBefore, afterWeight?.value, { activeBefore = false }, Modifier.weight(1f))
                }
                if (beforeWeight != null || afterWeight != null) Txt("Вес — ближайшее взвешивание в пределах 7 дней", T.small, Modifier.padding(top = 8.dp))
                if (p.comparisonErrors.isNotEmpty()) Btn("Повторить загрузку фото", { p.comparePhotos(setOf(before.id, after.id), force = true) }, Modifier.padding(top = 8.dp), Kind.Ghost)
                Section("Выбрать «$slot»")
                Txt("Нажми на миниатюру. Для другого конца пары выбери его подпись выше", T.small, Modifier.padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(126.dp)
                    .semantics { contentDescription = "Лента фото: $slot" }) {
                    itemsIndexed(shots, key = { _, photo -> photo.id.toString() }) { index, photo ->
                        val enabled = candidates.any { it.id == photo.id }
                        LaunchedEffect(photo.id) { p.thumb(photo.id) }
                        val chosen = photo.id == if (activeBefore) before.id else after.id
                        Column(Modifier.width(76.dp).alpha(if (enabled) 1f else 0.35f)
                            .then(if (enabled) Modifier.tap { choose(photo) } else Modifier)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Снимок ${index + 1}: ${photo.takenAt.date()} ${photo.takenAt.clock()}, для $slot"
                                selected = chosen
                                if (!enabled) disabled()
                            }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(8.dp)).background(C.Field)
                                .border(if (chosen) 2.dp else 1.dp, if (chosen) C.Lime else C.Border, RoundedCornerShape(8.dp))) {
                                val image = p.thumbs[photo.id]
                                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Ico(Icon.Image, 20.dp, C.Faint) }
                            }
                            Txt("${photo.takenAt.date().dm()}.${photo.takenAt.date().year % 100}", ts(11.sp, C.Muted))
                        }
                    }
                }
            }
        }
    }
    if (calendar && candidates.isNotEmpty() && before != null) {
        Sheet({ calendar = false }) {
            Txt("Дата снимка «$slot»", T.strong)
            Calendar(
                (if (activeBefore) before else after).takenAt.date(),
                candidates.minOf { it.takenAt.date() }, candidates.maxOf { it.takenAt.date() }, shots.map { it.takenAt.date() }.toSet(),
            ) { day -> nearestPhoto(candidates, day, zone)?.let(::choose) }
            Btn("Отмена", { calendar = false }, Modifier.fillMaxWidth(), Kind.Ghost)
        }
    }
}

@Composable
private fun CompareSlot(app: AppState, photo: Photo, label: String, active: Boolean, weight: Double?, select: () -> Unit, modifier: Modifier) {
    val image = app.progress.comparisonImages[photo.id]
    val error = app.progress.comparisonErrors[photo.id]
    Column(modifier.border(if (active) 2.dp else 1.dp, if (active) C.Lime else C.Border, RoundedCornerShape(12.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn(label, select, Modifier.fillMaxWidth().semantics { selected = active }, if (active) Kind.ChipOn else Kind.Chip)
        Box(Modifier.fillMaxWidth().aspectRatio(0.65f).clip(RoundedCornerShape(8.dp)).background(C.Field)
            .tap { app.progress.open(photo.id) }
            // Мост браузера удерживает текст удалённого дочернего узла в кнопке: ошибка висела бы и
            // после загрузки. Поэтому у кнопки только своя подпись, а ошибка — отдельной строкой ниже.
            .clearAndSetSemantics { contentDescription = "Открыть фото $label: ${photo.takenAt.date()}"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (error == null) Txt("Загружаю…", T.small, Modifier.padding(8.dp))
            else Ico(Icon.Image, 24.dp, C.Faint)
        }
        error?.let { Txt(it, T.small) }
        Txt("${photo.takenAt.date().dayMonth()} ${photo.takenAt.date().year}", T.small)
        weight?.let { Txt("${num(it)} кг", T.strong) }
    }
}

private fun PhotoInterval.text() = when {
    months == 0 && days == 0 -> "В один день"
    months == 0 -> "$days ${plural(days, "день", "дня", "дней") }"
    days == 0 -> "$months мес"
    else -> "$months мес $days ${plural(days, "день", "дня", "дней") }"
}
