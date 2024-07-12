package app.mehmaan.media.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.shibasis.reaktor.media.CameraComponent
import dev.shibasis.reaktor.media.Post
import dev.shibasis.reaktor.media.uploadPost
import io.ktor.http.*
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody.Companion.FORM
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File


fun sendRequest(file: File) {
    val client = OkHttpClient()

    // JSON part
    val jsonPart: RequestBody = "{\"field1\":\"example\",\"field2\":42}"
        .toRequestBody("application/json".toMediaType())

    // Image part
    val imagePart: RequestBody = file
        .asRequestBody("image/jpeg".toMediaType())

    // Multipart body
    val requestBody: RequestBody = MultipartBody.Builder()
        .setType(FORM)
        .addFormDataPart("data", null, jsonPart)
        .addFormDataPart("image", "testFile.jpg", imagePart)
        .build()
    val request: Request = Request.Builder()
        .url("https://mehmaan-post.shibasispatnaik.workers.dev/rafale.jpg")
        .post(requestBody)
        .build()
    val response: Response = client.newCall(request).execute()

    // Handle the response as needed
    if (response.isSuccessful) {
        val responseBody: String = response.body?.string() ?: ""
        // Do something with the response
    } else {
        // Handle the error
    }
}
@Composable
fun CameraScreen(cameraComponent: CameraComponent) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    LaunchedEffect(Unit) {
        cameraComponent.start(previewView)
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.onPrimary
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            IconButton(
                modifier = Modifier
                    .padding(16.dp)
                    .background(Color.LightGray)
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .align(Alignment.BottomCenter),
                onClick = {
                    scope.launch {
                        val file = cameraComponent.savePicture()
                        val contentType = ContentType.defaultForFile(file)
                        val fullSize = "https://mehmaan-post.shibasispatnaik.workers.dev/${file.name}-full"
                        val compressed = "https://mehmaan-post.shibasispatnaik.workers.dev/${file.name}-compressed"
                        val thumbnail = "https://mehmaan-post.shibasispatnaik.workers.dev/${file.name}-thumbnail"

//                        val result = httpClient.put(fullSize) {
//                            header("Content-Type", contentType)
//                            setBody(ByteArrayContent(file.readBytes(), contentType))
//                        }

//                        val compressedFile = File(cameraComponent.storageAdapter.getHomeDirectory(), "${file.name}-compressed")
//                        BitmapFactory.decodeFile(file.absolutePath).apply {
                            // needs api level checks, FeatureDetection abstraction
//                            compress(Bitmap.CompressFormat.WEBP, 80, compressedFile.outputStream())
//                        }
//                        val compressedResult = httpClient.put(compressed) {
//                            header("Content-Type", contentType)
//                            setBody(ByteArrayContent(compressedFile.readBytes(), contentType))
//                        }

                        uploadPost(
                            post = Post(
                                "", "", "rafale.jpg"
                            ),
                            imageBytes = file.readBytes(),
                            contentType = contentType
                        )
                        sendRequest(file)

                    }
                }
            ) {
                // Use Camera Button
                Icon(Icons.Default.Create, contentDescription = "Take Picture")
            }
        }
    }
}
