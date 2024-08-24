package dev.shibasis.tester.react.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.javascriptengine.JavaScriptSandbox
import com.google.common.util.concurrent.ListenableFuture
import dev.shibasis.reaktor.core.framework.Dispatch


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Button(onClick = {  }) {

            }
        }

        Dispatch.CPU.launch {
            javascript()
        }

    }

    suspend fun javascript(): String {
        val sandbox = JavaScriptSandbox.createConnectedInstanceAsync(this).get()
        val isolate = sandbox.createIsolate()
        val code = "function sum(a, b) { let r = a + b; return r.toString(); }; sum(3, 4)"
        val result = isolate.evaluateJavaScriptAsync(code).get()
        return result
    }

}
