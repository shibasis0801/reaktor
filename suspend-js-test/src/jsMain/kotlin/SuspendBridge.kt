import kotlinx.coroutines.delay
import love.forte.plugin.suspendtrans.annotation.JsPromise
import kotlin.js.JsExport

@JsExport
data class StaticData(val message: String)

@JsPromise
suspend fun fetchStaticData(): StaticData {
    delay(10)
    return StaticData("hello")
}
