package dev.shibasis.reaktor.media.image

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.io.network.http
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.isActive

// Needs move to cross platform WorkManager, instead of eager download
// Instant Tasks (to remove duplicate concurrent fires like this one below)
// Priority Tasks (work manager instant tasks)
// Scheduled Tasks (conditional)
// Capability based design, for guarantees around the implementation.
// https://chatgpt.com/c/68b1401b-f09c-8332-a958-ccb9890c2aaf
@Composable
fun AsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillBounds
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(url) {
        Dispatch.IO.launch {
            val fileName = url.split("/").last()
            if (fileName == "") return@launch

            val cachedBitmap = BitmapCache.retrieve(fileName)
            imageBitmap = if (cachedBitmap != null)
                cachedBitmap
            else {
                val cached = MultiLevelCache.retrieveWithFetch(fileName) {
                    val response = http.get(url)
                    if (response.status == HttpStatusCode.OK)
                        response.body<ByteArray>()
                    else null
                }

                if (cached != null)
                    BitmapCache.store(fileName, cached)

                BitmapCache.retrieve(fileName)
            }
        }
    }

    imageBitmap?.let {
        Image(
            bitmap = it,
            "url",
            modifier = modifier.onSizeChanged { size = it },
            contentScale = contentScale
        )
    }
}