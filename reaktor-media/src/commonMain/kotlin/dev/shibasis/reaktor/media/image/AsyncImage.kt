package dev.shibasis.reaktor.media.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage as CoilAsyncImage

@Composable
fun AsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "url",
    contentScale: ContentScale = ContentScale.FillBounds
) {
    CoilAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}
