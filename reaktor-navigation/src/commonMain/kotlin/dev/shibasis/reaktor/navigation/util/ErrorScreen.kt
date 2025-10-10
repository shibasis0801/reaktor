package dev.shibasis.reaktor.navigation.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import dev.shibasis.reaktor.navigation.InputSignal
import dev.shibasis.reaktor.navigation.route.Screen

class ErrorInputsSignal(
    val errorMessage: String? = null,
    val onRetry: () -> Unit = {}
): InputSignal()


class ErrorScreen(
    val defaultMessage: String = "Oops! Something went wrong."
): Screen<ErrorInputsSignal>(ErrorInputsSignal()) {
    @Composable
    override fun Render(props: ErrorInputsSignal) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = props.errorMessage ?: defaultMessage,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.Red,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Button(onClick = props.onRetry) {
                    Text(text = "Retry")
                }
            }
        }
    }
}
