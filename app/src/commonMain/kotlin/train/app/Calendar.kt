package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import train.core.calendarWeeks

private val monthNames = listOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")

/** Календарь с отмеченными датами; день без точки также можно выбрать. */
@Composable
fun Calendar(initial: LocalDate, min: LocalDate, max: LocalDate, marked: Set<LocalDate>, onSelect: (LocalDate) -> Unit) {
    var month by remember(initial) { mutableStateOf(LocalDate(initial.year, initial.month, 1)) }
    val minMonth = LocalDate(min.year, min.month, 1)
    val maxMonth = LocalDate(max.year, max.month, 1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CalendarArrow(Icon.Back, "Предыдущий месяц", month > minMonth) { month = month.minus(DatePeriod(months = 1)) }
            Txt("${monthNames[month.month.ordinal]} ${month.year}", T.strong, Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            CalendarArrow(Icon.Next, "Следующий месяц", month < maxMonth) { month = month.plus(DatePeriod(months = 1)) }
        }
        Row {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Txt(it, T.small, Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        calendarWeeks(month).forEach { week ->
            Row {
                week.forEach { day ->
                    val enabled = day != null && day in min..max
                    Box(Modifier.weight(1f).height(44.dp).padding(2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (day == initial) C.Lime else C.Field).alpha(if (day == null || enabled) 1f else 0.35f)
                        .then(if (enabled) Modifier.tap { onSelect(day) } else Modifier)
                        .then(if (day != null) Modifier.semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = "Дата: $day" + (if (day in marked) ", есть фото" else "") + if (!enabled) ", недоступно" else ""
                            selected = day == initial
                            if (!enabled) disabled()
                        } else Modifier), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            Txt(day.day.toString(), ts(color = if (day == initial) C.OnLime else C.Text))
                            if (day in marked) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).background(if (day == initial) C.OnLime else C.Lime, CircleShape))
                        }
                    }
                }
            }
        }
        Txt("Точка — день со снимком. Можно выбрать и соседний день", T.small, Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CalendarArrow(icon: Icon, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
        .then(if (enabled) Modifier.tap(onClick) else Modifier)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = label + if (!enabled) ", недоступно" else ""
            if (!enabled) disabled()
        }, contentAlignment = Alignment.Center) {
        Ico(icon, 20.dp, if (enabled) C.Soft else C.Dash)
    }
}
