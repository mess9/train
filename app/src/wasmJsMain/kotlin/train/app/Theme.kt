package train.app

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.core.BlockRole
import train.core.Rating
import train.core.TimeInput

// Цвета и размеры — из макета (тёмная тема)

object C {
    val Bg = Color(0xFF0F1012)
    val Text = Color(0xFFF1F2F4)
    val Soft = Color(0xFFD6D9DE)
    val Muted = Color(0xFFA6ABB3)
    val Faint = Color(0xFF868B93)
    val Card = Color(0xFF1A1C20)
    val Border = Color(0xFF2E3238)
    val Field = Color(0xFF24272C)
    val Bar = Color(0xFF131417)
    val Dash = Color(0xFF3A3F46)
    val Lime = Color(0xFFC8F25A)
    val OnLime = Color(0xFF111111)
    /** Оценки светофором: легко — зелёный, норм — жёлтый, тяжело — красный. */
    val Easy = Color(0xFF5ACF7E)
    val Normal = Color(0xFFF2C94C)
    val Hard = Color(0xFFFF6B6B)
    /** Удаление и «убрать»: красный, как «тяжело». */
    val Danger = Color(0xFFFF6B6B)
    val Heart = Color(0xFFFF5C8A)
    /** Выполненное: серый текст и фон карточки. */
    val Done = Color(0xFF6B7078)
    val DoneCard = Color(0xFF16171A)
    val DoneLine = Color(0xFF26282C)
    val NextBg = Color(0xFF23281A)
    val NextLine = Color(0xFF5E7A1F)
    val Thumb = Color(0xFFF2F2F0)
}

/** Оттенок блока: заголовок, фон карточек, линии. */
data class Tint(val tint: Color, val card: Color, val line: Color)

private val Warm = Tint(Color(0xFF93CFA0), Color(0xFF172019), Color(0xFF28392B))
private val Cool = Tint(Color(0xFFE6AF80), Color(0xFF211A15), Color(0xFF3A2C20))
private val Circuits = listOf(
    Tint(Color(0xFF93B6E6), Color(0xFF161C25), Color(0xFF253347)),
    Tint(Color(0xFFB8A6E0), Color(0xFF1C1825), Color(0xFF332A44)),
    Tint(Color(0xFF86C9C3), Color(0xFF152022), Color(0xFF22393B)),
)

/** Разминка — зелёная, заминка — оранжевая, основные блоки по кругу. */
fun tints(roles: List<BlockRole>): List<Tint> {
    var main = 0
    return roles.map {
        when (it) {
            BlockRole.WARMUP -> Warm
            BlockRole.COOLDOWN -> Cool
            BlockRole.MAIN -> Circuits[main++ % Circuits.size]
        }
    }
}

fun Rating.color() = when (this) {
    Rating.EASY -> C.Easy
    Rating.NORMAL -> C.Normal
    Rating.HARD -> C.Hard
}

fun Rating.onColor() = when (this) {
    Rating.EASY -> Color(0xFF0B2414)
    Rating.NORMAL -> Color(0xFF2A2004)
    Rating.HARD -> Color(0xFF2E0B0B)
}

/** Невыбранная кнопка оценки: тёмный фон с оттенком её цвета. */
fun Rating.dim() = when (this) {
    Rating.EASY -> Color(0xFF17261C)
    Rating.NORMAL -> Color(0xFF29251A)
    Rating.HARD -> Color(0xFF2C1A1A)
}

fun Rating.label() = when (this) {
    Rating.EASY -> "Легко"
    Rating.NORMAL -> "Норм"
    Rating.HARD -> "Тяжело"
}

fun Rating.letter() = when (this) {
    Rating.EASY -> "Л"
    Rating.NORMAL -> "Н"
    Rating.HARD -> "Т"
}

// Текст

fun ts(size: TextUnit = 15.sp, color: Color = C.Text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.35)

object T {
    val body = ts()
    val strong = ts(weight = FontWeight.SemiBold)
    val kicker = ts(13.sp, C.Muted, FontWeight.Medium)
    val h1 = ts(28.sp, weight = FontWeight.Bold)
    val sec = ts(13.sp, C.Muted, FontWeight.SemiBold)
    val meta = ts(13.sp, C.Muted)
    val small = ts(12.sp, C.Muted)
}

@Composable
fun Txt(text: String, style: TextStyle = T.body, modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) =
    BasicText(
        text, modifier, if (align != null) style.copy(textAlign = align) else style,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis, maxLines = maxLines,
    )

// Иконки — контуры из макета, viewBox 24

enum class Icon(vararg val paths: String) {
    Back("M15 6l-6 6 6 6"),
    Down("M6 9l6 6 6-6"),
    Up("M6 15l6-6 6 6"),
    Timer(circle(12f, 13f, 8f), "M12 9v4l2.5 2.5", "M9.5 2.5h5"),
    More(circle(12f, 5.5f, 1f), circle(12f, 12f, 1f), circle(12f, 18.5f, 1f)),
    Dumbbell("M6.5 7v10", "M3.5 9.5v5", "M17.5 7v10", "M20.5 9.5v5", "M6.5 12h11"),
    Home("M4 11l8-7 8 7", "M6 10v10h12V10"),
    History(circle(12f, 12f, 8f), "M12 8v4l3 2"),
    Ruler("M3 16l13-13 5 5-13 13z", "M7 12l2 2", "M10 9l2 2", "M13 6l2 2"),
    Heart("M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2z"),
    Note("M5 19h4l10-10-4-4L5 15z", "M13.5 6.5l4 4"),
    Plus("M12 5v14", "M5 12h14"),
    Camera("M4 8h3l2-2.5h6L17 8h3v11H4z", circle(12f, 13f, 3.5f)),
    Image("M4 5h16v14H4z", circle(9f, 10f, 1.6f), "M4 17l5-5 4 4 2.5-2.5L20 17"),
    Scale("M5 4h14l1 16H4z", "M9 9a3 3 0 0 1 6 0", "M12 9l1.5-2"),
    Swap("M7 7h12", "M16 4l3 3-3 3", "M17 17H5", "M8 14l-3 3 3 3"),
    Calendar("M4 6h16v14H4z", "M4 10h16", "M8 3.5v4", "M16 3.5v4"),
    Check("M5 12.5l4.5 4.5L19 7"),
    Close("M6 6l12 12", "M18 6L6 18"),
    Trash("M4.5 7h15", "M9.5 7V4.5h5V7", "M6.5 7l1 13h9l1-13", "M10 11v5", "M14 11v5"),
    Next("M9 6l6 6-6 6"),
    Skip(circle(12f, 12f, 8f), "M6.5 17.5l11-11"),
    Clock(circle(12f, 12f, 8f), "M12 8v4h3.5"),
    Layers("M12 4l8 4-8 4-8-4z", "M4 12l8 4 8-4", "M4 16l8 4 8-4"),
    Gear(circle(12f, 12f, 3f), "M10.1 4.64L10.52 2.11A10 10 0 0 1 13.48 2.11L13.9 4.64A7.6 7.6 0 0 1 15.86 5.45L17.95 3.96A10 10 0 0 1 20.04 6.05L18.55 8.14A7.6 7.6 0 0 1 19.36 10.1L21.89 10.52A10 10 0 0 1 21.89 13.48L19.36 13.9A7.6 7.6 0 0 1 18.55 15.86L20.04 17.95A10 10 0 0 1 17.95 20.04L15.86 18.55A7.6 7.6 0 0 1 13.9 19.36L13.48 21.89A10 10 0 0 1 10.52 21.89L10.1 19.36A7.6 7.6 0 0 1 8.14 18.55L6.05 20.04A10 10 0 0 1 3.96 17.95L5.45 15.86A7.6 7.6 0 0 1 4.64 13.9L2.11 13.48A10 10 0 0 1 2.11 10.52L4.64 10.1A7.6 7.6 0 0 1 5.45 8.14L3.96 6.05A10 10 0 0 1 6.05 3.96L8.14 5.45A7.6 7.6 0 0 1 10.1 4.64Z"),
}

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

/** `fill` — залить контур (сердечко «понравилось»). */
@Composable
fun Ico(icon: Icon, size: Dp = 22.dp, color: Color = C.Soft, stroke: Float = 2f, fill: Boolean = false) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            paths.forEach {
                if (fill) drawPath(it, color)
                drawPath(it, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// Кнопки и поверхности

/** Нажатие с лёгким толчком ([Haptic.Tick]); свой отклик — вызвать [haptic] в `onClick`, он перебьёт этот. */
fun Modifier.tap(onClick: () -> Unit) = clickable {
    haptic(Haptic.Tick)
    onClick()
}

/** Нажатие без подсветки — для фона листа. */
@Composable
fun Modifier.tapQuiet(onClick: () -> Unit) =
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)

enum class Kind { Primary, Ghost, Chip, ChipOn }

@Composable
fun Btn(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: Kind = Kind.Primary, big: Boolean = false, enabled: Boolean = true) {
    val (bg, fg) = when (kind) {
        Kind.Primary, Kind.ChipOn -> C.Lime to C.OnLime
        Kind.Ghost, Kind.Chip -> C.Field to C.Text
    }
    val chip = kind == Kind.Chip || kind == Kind.ChipOn
    val shape = RoundedCornerShape(if (big) 16.dp else if (chip) 10.dp else 14.dp)
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg)
            .then(if (enabled) Modifier.tap(onClick) else Modifier)
            .padding(
                horizontal = if (chip) 12.dp else 18.dp,
                vertical = if (big) 20.dp else if (chip) 8.dp else 14.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            label,
            ts(if (big) 17.sp else if (chip) 14.sp else 15.sp, fg, if (kind == Kind.Ghost || chip) FontWeight.SemiBold else FontWeight.Bold),
            align = TextAlign.Center,
        )
    }
}

@Composable
fun Tag(text: String, bg: Color = C.Field, fg: Color = C.Soft) {
    Txt(text, ts(12.sp, fg, FontWeight.SemiBold), Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun AccentTag(text: String) = Tag(text, Color(0xFF2B3318), C.Lime)

@Composable
fun LateTag(text: String) = Tag(text, Color(0xFF3A2616), Color(0xFFFFB27D))

@Composable
fun Surface(
    modifier: Modifier = Modifier,
    bg: Color = C.Card,
    border: Color? = null,
    radius: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.clip(shape).background(bg).then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier),
        content = content,
    )
}

/** Экран со скроллом и полями по краям, как `.scroll` в макете. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp), content = content)
}

@Composable
fun Header(kicker: String, title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Txt(kicker, T.kicker)
            Txt(title, T.h1, Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

/** Место на экране то же, что с отступами снаружи, но нажимается вся полоса — под палец, а не под иконку. */
@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).tap(onClick).padding(start = 2.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Ico(Icon.Back, 18.dp, C.Muted)
        Txt(label, ts(color = C.Muted, weight = FontWeight.Medium))
    }
}

@Composable
fun Section(title: String) = Txt(title.uppercase(), T.sec, Modifier.padding(top = 22.dp, bottom = 10.dp))

@Composable
fun IconBtn(icon: Icon, onClick: () -> Unit, size: Dp = 44.dp, color: Color = C.Soft, fill: Boolean = false, iconSize: Dp = 22.dp, label: String? = null) {
    // Подпись — для экранного диктора и e2e-тестов: у иконки нет текста, по которому её найти
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).tap(onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, iconSize, color, fill = fill) }
}

/**
 * Ввод поверх значения: в фокусе поле выглядит пустым, прежнее значение — подсказкой. Первая
 * цифра начинает число заново, а не дописывается к предзаполненному. Ушёл, ничего не набрав, —
 * значение прежнее: само поле ничего не стирает.
 */
private class Fresh {
    var focused by mutableStateOf(false)
    var typed by mutableStateOf(false)

    /** Поле показывает пустоту поверх значения. */
    val blank get() = focused && !typed

    fun focus(on: Boolean) {
        if (on && !focused) haptic(Haptic.Field)
        focused = on
        typed = false
    }
}

/**
 * Поле для чисел: цифровая клавиатура (`Decimal`), лишние символы отбрасываются при вводе.
 * Запятая — как точка. Ввод поверх значения — [Fresh].
 */
@Composable
fun NumField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    placeholder: String = "",
    style: TextStyle = ts(16.sp, weight = FontWeight.SemiBold),
    align: TextAlign = TextAlign.Center,
    bg: Color = C.Field,
    /** Единица мелко под числом: «кг», «повт» — колонка понятна без шапки. */
    unit: String? = null,
) {
    val fresh = remember { Fresh() }
    val shown = if (fresh.blank) "" else value
    BasicTextField(
        value = shown,
        onValueChange = { s ->
            if (s == shown) return@BasicTextField
            filterNumber(s, decimal)?.let {
                fresh.typed = true
                onChange(it)
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        textStyle = style.copy(textAlign = align),
        cursorBrush = SolidColor(C.Lime),
        modifier = modifier.onFocusChanged { fresh.focus(it.isFocused) },
        decorationBox = { inner ->
            FieldBox(shown.isEmpty(), if (fresh.blank && value.isNotEmpty()) value else placeholder, style, align, bg, unit, inner)
        },
    )
}

/**
 * Поле времени: набор как на микроволновке ([TimeInput]) — `130` → `1:30`. Курсор всегда в конце:
 * цифры въезжают справа, стирание сдвигает обратно.
 */
@Composable
fun TimeField(
    id: String,
    seconds: Int?,
    onSeconds: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = ts(16.sp, weight = FontWeight.SemiBold),
    bg: Color = C.Field,
    unit: String? = null,
) {
    var digits by remember(id) { mutableStateOf(seconds?.let(TimeInput::digitsOf).orEmpty()) }
    // Значение поменялось не из поля (другая строка, сервер) — показать его
    LaunchedEffect(seconds) { if (TimeInput.seconds(digits) != seconds) digits = seconds?.let(TimeInput::digitsOf).orEmpty() }
    val fresh = remember { Fresh() }
    val full = TimeInput.text(digits)
    // Ввод поверх значения ([Fresh]): первая цифра — новое время с нуля, а не дописка справа
    val text = if (fresh.blank) "" else full
    BasicTextField(
        value = TextFieldValue(text, TextRange(text.length)),
        onValueChange = { v ->
            if (v.text == text) return@BasicTextField
            // Пятая цифра не принимается, как на микроволновке
            val d = TimeInput.digits(v.text)
            if (d == digits && !fresh.blank) return@BasicTextField
            fresh.typed = true
            digits = d
            onSeconds(TimeInput.seconds(d))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = style.copy(textAlign = TextAlign.Center),
        cursorBrush = SolidColor(C.Lime),
        modifier = modifier.onFocusChanged { fresh.focus(it.isFocused) },
        decorationBox = { inner ->
            FieldBox(text.isEmpty(), if (fresh.blank && full.isNotEmpty()) full else "м:сс", style, TextAlign.Center, bg, unit, inner)
        },
    )
}

@Composable
private fun FieldBox(empty: Boolean, placeholder: String, style: TextStyle, align: TextAlign, bg: Color, unit: String?, inner: @Composable () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, C.Border, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp),
        contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                if (empty && placeholder.isNotEmpty()) Txt(placeholder, style.copy(color = C.Faint, textAlign = align), Modifier.fillMaxWidth())
                inner()
            }
            if (unit != null) Txt(unit, ts(9.sp, C.Faint, FontWeight.Medium).copy(lineHeight = 10.sp), align = TextAlign.Center)
        }
    }
}

private fun filterNumber(s: String, decimal: Boolean): String? {
    val t = s.replace('.', ',')
    if (t.length > 6) return null
    var comma = false
    for (ch in t) {
        when {
            ch.isDigit() -> {}
            ch == ',' && decimal && !comma -> comma = true
            else -> return null
        }
    }
    return t
}

/** Текстовое поле в рамке: поиск, ключ, комментарий. */
@Composable
fun TextArea(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = false, minHeight: Dp = 84.dp) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = T.body,
        cursorBrush = SolidColor(C.Lime),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(12.dp)).background(C.Field)
                    .border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Txt(placeholder, T.body.copy(color = C.Faint))
                inner()
            }
        },
    )
}

/**
 * Нижний лист поверх экрана: закрывает тап по фону или смахивание вниз — за ручку или за
 * содержимое, когда оно прокручено до верха. Недотянул — лист возвращается на место.
 */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    var closing by remember { mutableStateOf(false) }

    fun settle(velocity: Float) {
        // Дальше трети высоты или резкий взмах вниз — закрыть, иначе вернуть
        val close = drag > height / 3f || (velocity > 1200f && drag > 0f)
        if (close) closing = true
        scope.launch {
            animate(drag, if (close) height.toFloat() else 0f, initialVelocity = velocity) { v, _ -> drag = v }
            if (close) onDismiss()
        }
    }

    val swipe = remember {
        object : NestedScrollConnection {
            // Лист уже сдвинут, палец пошёл вверх — сперва поднять лист, потом прокручивать
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (closing || available.y >= 0f || drag <= 0f) return Offset.Zero
                val used = maxOf(available.y, -drag)
                drag += used
                return Offset(0f, used)
            }

            // Прокрутка упёрлась в верх, палец тянет вниз — лист едет за ним
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (closing || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                drag += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (closing || drag <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!closing && drag > 0f) settle(0f)
                return Velocity.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val shade = if (height > 0) 1f - (drag / height).coerceIn(0f, 1f) else 1f
        Box(Modifier.fillMaxSize().alpha(shade).background(Color(0x8C000000)).tapQuiet(onDismiss))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { height = it.height }
                .offset { IntOffset(0, drag.roundToInt()) }
                .nestedScroll(swipe)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(C.Card)
                .tapQuiet {} // тап по листу не закрывает его
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Ручка: за неё тянется всегда, даже когда содержимое прокручено
            Box(
                Modifier.fillMaxWidth().height(22.dp).draggable(
                    rememberDraggableState { d -> if (!closing) drag = (drag + d).coerceAtLeast(0f) },
                    Orientation.Vertical,
                    onDragStopped = { v -> if (!closing) settle(v) },
                ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(C.Dash))
            }
            content()
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun Dot(color: Color, ring: Boolean = false, size: Dp = 8.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (ring) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color)),
    )
}

/** Отметка выполненного подхода — точка цвета оценки; без оценки — серая. */
@Composable
fun RateDot(rating: Rating?, size: Dp = 12.dp) = Dot(rating?.color() ?: C.Done, size = size)
