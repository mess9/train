package train.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import train.core.BlockRole
import train.core.Rating

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
    val Easy = Color(0xFF6AB0FF)
    val Normal = Color(0xFFE3E5E8)
    val Hard = Color(0xFFFF9A52)
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
    Rating.EASY -> Color(0xFF0B1A2C)
    Rating.NORMAL -> C.OnLime
    Rating.HARD -> Color(0xFF2A1404)
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
    BasicText(text, modifier, if (align != null) style.copy(textAlign = align) else style, maxLines = maxLines)

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
    Gear(circle(12f, 12f, 3f), "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "M4.9 4.9l2.1 2.1", "M17 17l2.1 2.1", "M4.9 19.1L7 17", "M17 7l2.1-2.1"),
}

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

@Composable
fun Ico(icon: Icon, size: Dp = 22.dp, color: Color = C.Soft, stroke: Float = 2f) {
    val paths = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

// Кнопки и поверхности

fun Modifier.tap(onClick: () -> Unit) = clickable(onClick = onClick)

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

@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 10.dp, bottom = 4.dp).clip(RoundedCornerShape(8.dp)).tap(onClick).padding(vertical = 4.dp, horizontal = 2.dp),
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
fun IconBtn(icon: Icon, onClick: () -> Unit, size: Dp = 44.dp, color: Color = C.Soft) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).tap(onClick), contentAlignment = Alignment.Center) { Ico(icon, color = color) }
}

/**
 * Поле для чисел: цифровая клавиатура (`Decimal`), лишние символы отбрасываются при вводе.
 * Запятая — как точка.
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
) {
    BasicTextField(
        value = value,
        onValueChange = { s -> filterNumber(s, decimal)?.let(onChange) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        textStyle = style.copy(textAlign = align),
        cursorBrush = SolidColor(C.Lime),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, C.Border, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp),
                contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Txt(placeholder, style.copy(color = C.Faint, textAlign = align), Modifier.fillMaxWidth())
                inner()
            }
        },
    )
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

/** Нижний лист поверх экрана: тап по фону закрывает. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x8C000000)).tapQuiet(onDismiss))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(C.Card)
                .tapQuiet {} // тап по листу не закрывает его
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(CircleShape).background(C.Dash))
            content()
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun Dot(color: Color, ring: Boolean = false) {
    Box(
        Modifier.size(8.dp).clip(CircleShape)
            .then(if (ring) Modifier.border(1.5.dp, C.Lime, CircleShape) else Modifier.background(color)),
    )
}
