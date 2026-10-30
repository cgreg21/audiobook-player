package org.nativescript.audiobookplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val tag: String,
    val downloadUrl: String,
    val releaseNotes: String,
)

class AppUpdateManager(private val context: Context) {
    fun findUpdate(): AppUpdate? {
        val connection = URL("$RELEASES_URL?per_page=10").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "AudiobookPlayer-Android")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("GitHub a répondu ${connection.responseCode}.")
            }
            val releases = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val release = (0 until releases.length())
                .asSequence()
                .mapNotNull(releases::optJSONObject)
                .firstOrNull { candidate ->
                    !candidate.optBoolean("draft") &&
                        candidate.optJSONArray("assets")?.let { assets ->
                            (0 until assets.length()).any { index ->
                                assets.optJSONObject(index)?.optString("name")
                                    ?.endsWith(".apk", ignoreCase = true) == true
                            }
                        } == true
                } ?: return null

            val tag = release.optString("tag_name").takeIf(String::isNotBlank)
                ?: error("La release GitHub n’a pas de version.")
            if (compareVersions(tag, currentVersion()) <= 0) return null

            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length())
                .asSequence()
                .mapNotNull(assets::optJSONObject)
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: error("Aucun fichier APK disponible dans cette release.")
            val downloadUrl = apk.optString("browser_download_url")
            if (!downloadUrl.startsWith("https://")) error("L’URL de téléchargement de l’APK n’est pas sécurisée.")

            return AppUpdate(
                tag = tag,
                downloadUrl = downloadUrl,
                releaseNotes = release.optString("body"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(update: AppUpdate): File {
        val directory = File(context.cacheDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Impossible de préparer le dossier de mise à jour.")
        }
        val temporary = File(directory, "update.apk.part")
        val target = File(directory, "update.apk")
        val connection = URL(update.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "AudiobookPlayer-Android")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Le téléchargement a échoué (HTTP ${connection.responseCode}).")
            }
            if (connection.url.protocol != "https") {
                error("Le serveur de téléchargement a redirigé vers une connexion non sécurisée.")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (temporary.length() == 0L) error("Le fichier APK téléchargé est vide.")
            if (target.exists() && !target.delete()) error("Impossible de remplacer l’ancienne mise à jour.")
            if (!temporary.renameTo(target)) error("Impossible de finaliser le fichier APK.")
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun installerUri(apk: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun currentVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): Pair<List<Int>, Boolean> {
            val normalized = value.removePrefix("v").removePrefix("V")
            val segments = normalized.split('-', limit = 2)
            val numbers = segments[0].split('.').map { it.toIntOrNull() ?: 0 }
            return numbers to (segments.size > 1)
        }

        val (leftNumbers, leftPrerelease) = parts(left)
        val (rightNumbers, rightPrerelease) = parts(right)
        for (index in 0 until maxOf(leftNumbers.size, rightNumbers.size)) {
            val difference = (leftNumbers.getOrElse(index) { 0 } - rightNumbers.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return when {
            leftPrerelease == rightPrerelease -> 0
            leftPrerelease -> -1
            else -> 1
        }
    }

    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/cgreg21/audiobook-player/releases"
    }
}
