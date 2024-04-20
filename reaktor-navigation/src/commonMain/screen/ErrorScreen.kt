package app.mehmaan.navigation.screen

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

data class ErrorScreenProps(
    val errorMessage: String = "Oops! Something went wrong.",
    val onRetry: () -> Unit = {}
): Props()

@Composable
fun ErrorScreen(
    props: ErrorScreenProps
) {
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
                text = props.errorMessage,
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
