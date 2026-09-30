package dev.shibasis.reaktor.media.audio

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.web.TapSheet
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Promise

class WebAudioPlayer : AudioPlayerAdapter<Unit>(Unit) {
    private var element: dynamic = null
    private var address: String? = null
    private var key: String? = null
    private var ticker: Int? = null

    override suspend fun play(key: String, bytes: ByteArray, fromMillis: Long) {
        stop()
        val source = objectUrl(bytes, containerOf(bytes))
        val audio = js("new Audio()")
        audio.preload = "auto"
        audio.src = source
        audio.onended = { _: dynamic -> stop() }
        element = audio
        address = source
        this.key = key
        if (fromMillis > 0) audio.currentTime = fromMillis / 1000.0
        try {
            start(audio).await()
            if (element === audio) tick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (element !== audio) return
            when (error.asDynamic().name as? String) {
                "NotAllowedError" -> offerPlayback(audio)
                "AbortError" -> Unit
                else -> {
                    Logger.e(error) { "Audio playback could not start" }
                    stop()
                }
            }
        }
    }

    override fun pause() {
        element?.pause()
        stopTicking()
        publish()
    }

    override fun resume() {
        val audio = element ?: return
        start(audio).catch { publish() }
        tick()
    }

    override fun stop() {
        stopTicking()
        val audio = element
        if (audio != null) {
            audio.onended = null
            audio.pause()
            audio.removeAttribute("src")
            audio.load()
        }
        element = null
        key = null
        address?.let { revoke(it) }
        address = null
        playbackState.value = Playback.Idle
    }

    private fun offerPlayback(audio: dynamic) {
        val sheet = TapSheet.open("Play voice message") { if (element === audio) stop() }
        sheet.button("Play") {
            sheet.close()
            if (element === audio) {
                start(audio).catch { publish() }
                tick()
            }
        }
    }

    private fun start(audio: dynamic): Promise<Any?> = audio.play().unsafeCast<Promise<Any?>>()

    private fun tick() {
        stopTicking()
        publish()
        ticker = window.setInterval({ publish() }, 100)
    }

    private fun stopTicking() {
        ticker?.let { window.clearInterval(it) }
        ticker = null
    }

    private fun publish() {
        val audio = element ?: return
        val playing = key ?: return
        playbackState.value = Playback.Active(
            key = playing,
            positionMillis = millis(audio.currentTime),
            durationMillis = millis(audio.duration),
            playing = !(audio.paused as Boolean),
        )
    }
}

private fun millis(seconds: dynamic): Long {
    val value = seconds as? Double ?: return 0L
    return if (value.isNaN() || value.isInfinite()) 0L else (value * 1000).toLong()
}

private fun containerOf(bytes: ByteArray): String = when {
    bytes.size > 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> "audio/mp4"
    bytes.size > 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
        bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte() -> "audio/webm"
    bytes.size > 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
        bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte() -> "audio/ogg"
    bytes.size > 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() -> "audio/wav"
    else -> "audio/mpeg"
}

private fun objectUrl(bytes: ByteArray, type: String): String {
    val view = js("new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length)")
    val options = js("({})")
    options.type = type
    val blob = js("new Blob([view], options)")
    return js("URL.createObjectURL(blob)") as String
}

private fun revoke(address: String) {
    js("URL.revokeObjectURL(address)")
}
