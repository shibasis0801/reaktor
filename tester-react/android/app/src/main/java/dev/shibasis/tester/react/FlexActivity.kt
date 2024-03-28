package dev.shibasis.tester.react

import android.os.Bundle
import android.view.Gravity
import android.view.View.generateViewId
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.children
import androidx.core.view.marginBottom
import androidx.core.view.setMargins
import androidx.core.view.setPadding
import com.google.flatbuffers.kotlin.value
import dev.shibasis.flatinvoker.core.EncodingComplexCase
import dev.shibasis.flatinvoker.core.serialization.encodeToFlexBuffer
import dev.shibasis.reaktor.core.framework.Dispatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.system.measureTimeMillis

val WRAP_CONTENT = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
)

val MATCH_PARENT = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.MATCH_PARENT
)

class FlexActivity: ComponentActivity() {
    val textFlow = MutableStateFlow("Encoding Time")
    val complexCase = EncodingComplexCase()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(render(textFlow) {
            val thread = Thread(::shibasispatnaikrepeat, "ShibasisPatnaik")
            thread.start()
            Dispatch.Main.launch {
                delay(5000)
                thread.join()
            }
        })
    }

    fun shibasispatnaik() {
        val time = measureTimeMillis { encodeToFlexBuffer(complexCase) }
        textFlow.value = "Encoding Time: $time ms"
    }

    fun shibasispatnaikrepeat() {
        repeat(100) {
            encodeToFlexBuffer(complexCase)
        }
    }

    // Didn't want to use compose, because I need to measure size impact.
    private fun render(
        textFlow: MutableStateFlow<String>,
        onClick: () -> Unit = {}
    ) = LinearLayout(this).apply {
        layoutParams = MATCH_PARENT
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(16)

        val textView = TextView(context).apply {
            id = generateViewId()
            text = "Encoding Time"
            layoutParams = WRAP_CONTENT.apply { setMargins(16) }
        }

        Dispatch.Main.launch {
            textFlow.collect {
                textView.text = it
            }
        }

        val button = Button(context).apply {
            id = generateViewId()
            text = "Start Encoding"
            layoutParams = WRAP_CONTENT.apply { setMargins(16) }
            setOnClickListener { onClick() }
        }

        addView(textView)
        addView(button)
    }
}