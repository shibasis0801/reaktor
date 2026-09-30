package dev.shibasis.reaktor.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.absoluteValue
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NotificationAvatars(context: Context) {
    private val memory = LruCache<String, Bitmap>(MemoryEntries)
    private val folder = File(context.cacheDir, "reaktor-notification-avatars")

    suspend fun of(person: NotificationPerson): Bitmap =
        person.photoUrl?.takeIf { it.startsWith("https://") }?.let { url -> photo(url) } ?: initial(person)

    private suspend fun photo(url: String): Bitmap? = memory.get(url) ?: withContext(Dispatchers.IO) {
        runCatching { stored(url) ?: downloaded(url) }.getOrNull()
    }?.also { memory.put(url, it) }

    private fun file(url: String): File =
        File(folder, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".png")

    private fun stored(url: String): Bitmap? = file(url).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun downloaded(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TimeoutMillis
        connection.readTimeout = TimeoutMillis
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val source = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            circle(source).also { avatar ->
                folder.mkdirs()
                file(url).outputStream().use { avatar.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun circle(source: Bitmap): Bitmap {
        val side = min(source.width, source.height)
        val size = min(side, AvatarPixels)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), paint)
        return output
    }

    private fun initial(person: NotificationPerson): Bitmap {
        val output = Bitmap.createBitmap(AvatarPixels, AvatarPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val center = AvatarPixels / 2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette[(person.id.hashCode()).absoluteValue % Palette.size] }
        canvas.drawCircle(center, center, center, fill)
        val letter = person.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = AvatarPixels * 0.46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(letter, center, center - (ink.descent() + ink.ascent()) / 2, ink)
        return output
    }

    private companion object {
        const val MemoryEntries = 32
        const val TimeoutMillis = 4_000
        const val AvatarPixels = 192
        val Palette = intArrayOf(
            0xFFC0504D.toInt(),
            0xFF4F81BD.toInt(),
            0xFF9BBB59.toInt(),
            0xFF8064A2.toInt(),
            0xFFF79646.toInt(),
            0xFF4BACC6.toInt(),
        )
    }
}
