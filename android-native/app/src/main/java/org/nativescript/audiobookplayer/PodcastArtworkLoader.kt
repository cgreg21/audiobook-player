package org.nativescript.audiobookplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class PodcastArtworkLoader(context: Context) {
    private val directory = File(context.cacheDir, "podcast-covers")
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun load(view: ImageView, address: String?, allowNetwork: Boolean) {
        view.tag = address
        view.setImageResource(android.R.drawable.ic_btn_speak_now)
        if (address.isNullOrBlank()) return
        memory.get(address)?.let {
            view.setImageBitmap(it)
            return
        }
        executor.execute {
            try {
                memory.get(address)?.let { bitmap ->
                    handler.post { if (view.tag == address) view.setImageBitmap(bitmap) }
                    return@execute
                }
                val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val file = File(directory, key)
                if (!file.exists()) {
                    if (!allowNetwork) return@execute
                    if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create artwork cache")
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    val temporary = File(directory, "$key.tmp")
                    try {
                        if (connection.responseCode !in 200..299) {
                            throw IOException("Artwork HTTP ${connection.responseCode}")
                        }
                        connection.inputStream.use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > 8 * 1024 * 1024) throw IOException("Artwork exceeds 8 MB")
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(temporary.path, bounds)
                        if (bounds.outWidth <= 0) throw IOException("Invalid podcast artwork")
                        if (!temporary.renameTo(file)) throw IOException("Cannot save podcast artwork")
                    } finally {
                        connection.disconnect()
                        temporary.delete()
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 600 || bounds.outHeight / inSampleSize > 600) {
                        inSampleSize *= 2
                    }
                }
                val bitmap = BitmapFactory.decodeFile(file.path, options)
                    ?: throw IOException("Cannot decode podcast artwork")
                memory.put(address, bitmap)
                handler.post { if (view.tag == address) view.setImageBitmap(bitmap) }
            } catch (error: IOException) {
                Log.w("PodcastArtwork", "Unable to load podcast cover", error)
            }
        }
    }

    fun close() {
        executor.shutdown()
    }
}
