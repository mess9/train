package train.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.document
import kotlinx.browser.window
import train.client.ApiClient

private val Bg = Color(0xFF121212)
private val Muted = Color(0xFF9A9A9A)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}

@Composable
fun App() {
    var server by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) {
        server = runCatching { ApiClient(HttpClient(Js), window.location.origin).info() }
            .fold({ "${it.name} ${it.version}" }, { "нет связи: ${it.message}" })
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("Тренировки", style = TextStyle(Color.White, 22.sp, FontWeight.Bold))
        BasicText("сервер: $server", style = TextStyle(Muted, 14.sp))
    }
}
