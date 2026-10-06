package dev.shibasis.reaktor.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.*
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.web.*
import org.koin.dsl.koinApplication
import kotlinx.coroutines.launch

/** Real-window acceptance fixture. Build on M1; launch compiled classes on the UI Mac. */
fun main() {
    val koin = koinApplication {}
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
    val runtime = graph.Node { WebRuntime(it) }
    application {
        val first = rememberWebViewState()
        val second = rememberWebViewState()
        val firstFailure by first.failure.collectAsState()
        val secondFailure by second.failure.collectAsState()
        val firstSession by first.session.collectAsState()
        val firstPage by remember(firstSession) {
            firstSession?.state ?: kotlinx.coroutines.flow.MutableStateFlow(WebSessionState())
        }.collectAsState()
        var visible by remember { mutableStateOf(true) }
        var modal by remember { mutableStateOf(false) }
        val probeScope = rememberCoroutineScope()
        var inspection by remember { mutableStateOf("") }
        Window(onCloseRequest = { graph.close(); koin.close(); exitApplication() }, title = "Compose native WebView acceptance") {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Row {
                        Button(onClick = { visible = !visible }) { Text("Hide / show") }
                        Button(onClick = { modal = true }) { Text("Modal") }
                        Button(onClick = { first.zoom(1.2) }) { Text("Zoom") }
                        Button(onClick = { first.focus() }) { Text("Focus first") }
                        Button(onClick = { second.focus() }) { Text("Focus second") }
                        Button(onClick = { probeScope.launch {
                            inspection = runCatching { firstSession?.evaluate("({body:document.body.innerText,width:innerWidth,height:innerHeight})") }.toString()
                        } }) { Text("Inspect") }
                    }
                    if (firstFailure != null || secondFailure != null) Text("Host failure: $firstFailure / $secondFailure")
                    Text("Session: ${firstSession != null}; URL: ${firstPage.committedUrl}; error: ${firstPage.error}")
                    if (inspection.isNotEmpty()) Text(inspection)
                    Row(Modifier.weight(1f)) {
                        ReaktorWebView(runtime, WebContent.Html("<h1>First native pane</h1><input placeholder='Type नमस्ते 🌍'><p>Resize, focus and hide/show</p>"),
                            Modifier.weight(1f).fillMaxHeight(), first, visible = visible && !modal)
                        ReaktorWebView(runtime, WebContent.Html("<h1>Second native pane</h1><textarea placeholder='Independent surface'></textarea>"),
                            Modifier.weight(1f).fillMaxHeight(), second, visible = !modal)
                    }
                }
                if (modal) AlertDialog(onDismissRequest = { modal = false }, title = { Text("Compose modal") },
                    text = { Text("Native surfaces are detached while this modal is visible.") },
                    confirmButton = { TextButton(onClick = { modal = false }) { Text("Close") } })
            }
        }
    }
}
