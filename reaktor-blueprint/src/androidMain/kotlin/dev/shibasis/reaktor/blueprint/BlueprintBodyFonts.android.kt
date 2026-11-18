package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver

@Composable
internal actual fun blueprintBodyFontResolverFactory(): () -> FontFamily.Resolver {
    val context = LocalContext.current
    return remember(context) { { createFontFamilyResolver(context) } }
}
