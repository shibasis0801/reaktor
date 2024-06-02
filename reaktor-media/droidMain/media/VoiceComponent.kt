package app.mehmaan.media.media

import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import app.mehmaan.core.framework.Component
import app.mehmaan.core.framework.ControllerEvent
import java.io.File
import java.io.IOException

// Needs rewrite
class VoiceComponent(
//    val fileInteractor: FileAdapter
): Component {

    var mediaRecorder: MediaRecorder? = MediaRecorder() // ignore deprecation
    enum class Reason { Normal, Speech, High }


    @RequiresApi(Build.VERSION_CODES.O)
    fun start(reason: Reason = Reason.Speech): File? {
        return null
        var file: File? = null
        when(reason) {
            Reason.Speech -> {
//                file = fileInteractor.createTempFile()
                mediaRecorder?.apply {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setAudioSamplingRate(32000)
                    try {
                        setOutputFile(file)
                        prepare()
                        start()
                    } catch (e: IOException) {
                        Log.e("VoiceRecorder", "prepare() failed")
                    }
                }
            }
            else -> {
                throw Error("Not supported")
            }
        }

        return file
    }

    fun stop() {
        mediaRecorder?.apply { stop(); release() }
        mediaRecorder = null
    }
    override fun handle(event: ControllerEvent) {
        when(event) {
            is ControllerEvent.Stop -> stop()
            else -> {}
        }
    }
}
