package app.mehmaan.media.media

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.IOException

object AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun start(file: File) {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.path)
            try {
                prepare()
            } catch (e: IOException) {
                Log.e("VoiceRecorder", "prepare() failed")
            }
            start()
        }
    }

    fun stop() {
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null
    }
}
