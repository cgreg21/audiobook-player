package org.nativescript.audiobookplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, WAITING_FOR_WIFI, ERROR }

data class PodcastDownload(
    val key: String,
    val feedUrl: String,
    val episodeId: String,
    val title: String,
    val status: DownloadStatus,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val mobileAllowed: Boolean = false,
)

class PodcastDownloadQueue(
    private val context: Context,
    private val database: LibraryDatabase,
    serviceAlreadyStarted: Boolean = false,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val downloads = mutableListOf<PodcastDownload>()
    private val jobs = mutableMapOf<String, TransferJob>()
    private var lastProgressSaveAt = 0L
    @Volatile private var onChanged: () -> Unit = {}
    private var serviceRequested = serviceAlreadyStarted
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = onNetworkChanged()

        override fun onCapabilitiesChanged(
            network: android.net.Network,
            networkCapabilities: NetworkCapabilities,
        ) = onNetworkChanged()

        override fun onLost(network: android.net.Network) = onNetworkChanged()
    }

    init {
        synchronized(this) {
            database.downloadQueue().forEach { saved ->
                val podcast = database.podcast(saved.feedUrl) ?: return@forEach
                val episode = podcast.episodes.firstOrNull { it.id == saved.episodeId } ?: return@forEach
                if (episode.localPath != null) return@forEach
                val target = destination(podcast, episode)
                if (target.isFile && target.length() > 0) {
                    database.savePodcast(
                        podcast.copy(episodes = podcast.episodes.map {
                            if (it.id == episode.id) it.copy(localPath = target.absolutePath) else it
                        }),
                        database.podcasts().indexOfFirst { it.feedUrl == saved.feedUrl },
                    )
                    return@forEach
                }
                val partialSize = partialFile(saved.feedUrl, saved.episodeId)?.length() ?: 0L
                downloads += saved.copy(
                    status = if (saved.status == DownloadStatus.DOWNLOADING) {
                        DownloadStatus.QUEUED
                    } else {
                        saved.status
                    },
                    done = maxOf(saved.done, partialSize),
                )
            }
            persistLocked()
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        notifyChanged()
        startNext()
    }

    @Synchronized
    fun items(): List<PodcastDownload> = downloads.toList()

    fun setOnChanged(listener: () -> Unit) {
        onChanged = listener
    }

    fun enqueue(feedUrl: String, episodeId: String, mobileAllowed: Boolean = false): String? {
        val podcast = database.podcast(feedUrl) ?: return null
        val episode = podcast.episodes.firstOrNull { it.id == episodeId } ?: return null
        if (episode.localPath != null) return null
        val key = key(feedUrl, episodeId)
        synchronized(this) {
            if (downloads.any { it.key == key }) return key
            downloads += PodcastDownload(
                key = key,
                feedUrl = feedUrl,
                episodeId = episodeId,
                title = episode.title,
                status = DownloadStatus.QUEUED,
                mobileAllowed = mobileAllowed,
            )
            persistLocked()
        }
        notifyChanged()
        startNext()
        return key
    }

    fun pause(key: String) {
        synchronized(this) {
            val index = downloads.indexOfFirst { it.key == key }
            if (index < 0) return
            val item = downloads[index]
            when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    jobs[key]?.paused?.set(true)
                    downloads[index] = item.copy(status = DownloadStatus.PAUSED)
                }
                DownloadStatus.QUEUED -> downloads[index] = item.copy(status = DownloadStatus.PAUSED)
                else -> return
            }
            persistLocked()
        }
        notifyChanged()
    }

    fun resume(key: String) {
        synchronized(this) {
            val index = downloads.indexOfFirst { it.key == key }
            if (index < 0) return
            val item = downloads[index]
            if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.ERROR) return
            downloads[index] = item.copy(status = DownloadStatus.QUEUED, error = null)
            persistLocked()
        }
        notifyChanged()
        startNext()
    }

    fun cancel(key: String) {
        var partialToDelete: File? = null
        synchronized(this) {
            val item = downloads.firstOrNull { it.key == key } ?: return
            val active = jobs[key] != null
            jobs[key]?.cancelled?.set(true)
            if (!active) {
                partialToDelete = partialFile(item.feedUrl, item.episodeId)
            }
            downloads.removeAll { it.key == key }
            persistLocked()
        }
        partialToDelete?.delete()
        notifyChanged()
        startNext()
    }

    fun cancelAll() {
        items().forEach { cancel(it.key) }
    }

    fun pauseAll() {
        synchronized(this) {
            downloads.indices.forEach { index ->
                val item = downloads[index]
                if (item.status == DownloadStatus.DOWNLOADING) {
                    jobs[item.key]?.paused?.set(true)
                    downloads[index] = item.copy(status = DownloadStatus.PAUSED)
                } else if (item.status == DownloadStatus.QUEUED) {
                    downloads[index] = item.copy(status = DownloadStatus.PAUSED)
                }
            }
            persistLocked()
        }
        notifyChanged()
    }

    fun onNetworkPolicyChanged() = onNetworkChanged()

    @Synchronized
    fun downloadedUsage(): Map<String, Pair<Int, Long>> {
        val usage = mutableMapOf<String, Pair<Int, Long>>()
        database.podcasts().forEach { podcast ->
            val downloaded = podcast.episodes.mapNotNull { episode ->
                episode.localPath?.let { path -> File(path).takeIf(File::exists) }
            }
            usage[podcast.feedUrl] = downloaded.size to downloaded.sumOf(File::length)
        }
        return usage
    }

    fun removeDownload(feedUrl: String, episodeId: String) {
        synchronized(this) {
            val key = key(feedUrl, episodeId)
            jobs[key]?.cancelled?.set(true)
            downloads.removeAll { it.key == key }
            persistLocked()
            val podcast = database.podcast(feedUrl) ?: return
            val episode = podcast.episodes.firstOrNull { it.id == episodeId } ?: return
            episode.localPath?.let(::deleteDownloadedFile)
            partialFile(feedUrl, episodeId)?.delete()
            database.savePodcast(
                podcast.copy(episodes = podcast.episodes.map {
                    if (it.id == episodeId) it.copy(localPath = null) else it
                }),
                database.podcasts().indexOfFirst { it.feedUrl == feedUrl },
            )
        }
        notifyChanged()
        startNext()
    }

    fun clearDownloads() {
        synchronized(this) {
            jobs.values.forEach { it.cancelled.set(true) }
            val pending = downloads.toList()
            downloads.clear()
            persistLocked()
            pending.forEach { partialFile(it.feedUrl, it.episodeId)?.delete() }
            database.podcasts().forEach { podcast ->
                podcast.episodes.filter { it.localPath != null }.forEach { episode ->
                    episode.localPath?.let(::deleteDownloadedFile)
                }
                database.savePodcast(
                    podcast.copy(episodes = podcast.episodes.map { it.copy(localPath = null) }),
                    database.podcasts().indexOfFirst { it.feedUrl == podcast.feedUrl },
                )
            }
        }
        notifyChanged()
    }

    fun cancelFeedAndRun(feedUrl: String, action: () -> Unit) {
        synchronized(this) {
            val feedDownloads = downloads.filter { it.feedUrl == feedUrl }
            feedDownloads.forEach { item ->
                jobs[item.key]?.cancelled?.set(true)
                if (jobs[item.key] == null) partialFile(item.feedUrl, item.episodeId)?.delete()
            }
            downloads.removeAll { it.feedUrl == feedUrl }
            persistLocked()
            action()
        }
        notifyChanged()
        startNext()
    }

    private fun startNext() {
        var transfer: Pair<PodcastDownload, TransferJob>? = null
        var changed = false
        synchronized(this) {
            if (jobs.isEmpty()) {
                val onMobile = isMobileNetwork()
                val mobileAllowed = database.setting("downloads.mobile", "0") == "1"
                if (onMobile && !mobileAllowed) {
                    downloads.indices.forEach { index ->
                        val queued = downloads[index]
                        if (queued.status == DownloadStatus.QUEUED && !queued.mobileAllowed) {
                            downloads[index] = queued.copy(status = DownloadStatus.WAITING_FOR_WIFI)
                            changed = true
                        }
                    }
                }
                val index = downloads.indexOfFirst {
                    it.status == DownloadStatus.QUEUED &&
                        (!onMobile || mobileAllowed || it.mobileAllowed)
                }
                if (index >= 0) {
                    val item = downloads[index].copy(status = DownloadStatus.DOWNLOADING)
                    downloads[index] = item
                    val podcast = database.podcast(item.feedUrl)
                    val episode = podcast?.episodes?.firstOrNull { it.id == item.episodeId }
                    if (podcast == null || episode == null) {
                        downloads.removeAt(index)
                        changed = true
                    } else {
                        val target = destination(podcast, episode)
                        val job = TransferJob(
                            episode.remoteUrl,
                            target,
                            feedUrl = item.feedUrl,
                            episodeId = item.episodeId,
                        )
                        jobs[item.key] = job
                        transfer = item to job
                    }
                }
                if (changed || transfer != null) persistLocked()
            }
        }
        if (changed) notifyChanged()
        val (item, job) = transfer ?: return
        notifyChanged()
        Thread { transfer(item.key, job) }.start()
    }

    private fun transfer(key: String, job: TransferJob) {
        var connection: HttpURLConnection? = null
        try {
            val partial = File("${job.target.absolutePath}.part")
            partial.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) error("Impossible de créer le dossier de téléchargement.")
            }
            val activeConnection = URL(job.url).openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.connectTimeout = 20_000
            activeConnection.readTimeout = 30_000
            activeConnection.setRequestProperty("User-Agent", "AudiobookPlayer/1.0 (Android)")
            val existing = partial.length()
            if (existing > 0) activeConnection.setRequestProperty("Range", "bytes=$existing-")
            activeConnection.connect()
            val statusCode = activeConnection.responseCode
            if (statusCode !in 200..299) error("Échec du téléchargement (HTTP $statusCode).")
            val append = existing > 0 && statusCode == HttpURLConnection.HTTP_PARTIAL
            val offset = if (append) existing else 0L
            if (!append && existing > 0) partial.delete()
            val length = activeConnection.contentLengthLong
            var done = offset
            publish(key) { it.copy(done = done, total = if (length >= 0) offset + length else 0) }
            activeConnection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastUpdate = 0L
                    while (true) {
                        if (job.cancelled.get()) {
                            partial.delete()
                            finishCancelled(key)
                            return
                        }
                        if (job.paused.get()) {
                            finishPaused(key, done, if (length >= 0) offset + length else 0)
                            return
                        }
                        if (job.waitingForWifi.get()) {
                            finishWaitingForWifi(key)
                            return
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (job.cancelled.get()) {
                            partial.delete()
                            finishCancelled(key)
                            return
                        }
                        if (job.paused.get()) {
                            finishPaused(key, done, if (length >= 0) offset + length else 0)
                            return
                        }
                        if (job.waitingForWifi.get()) {
                            finishWaitingForWifi(key)
                            return
                        }
                        output.write(buffer, 0, count)
                        done += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 300) {
                            lastUpdate = now
                            publish(key) { it.copy(done = done, total = if (length >= 0) offset + length else 0) }
                        }
                    }
                }
            }
            if (job.cancelled.get()) {
                partial.delete()
                finishCancelled(key)
                return
            }
            if (job.paused.get()) {
                finishPaused(key, done, if (length >= 0) offset + length else 0)
                return
            }
            if (job.waitingForWifi.get()) {
                finishWaitingForWifi(key)
                return
            }
            if (!partial.renameTo(job.target)) error("Impossible de finaliser le fichier téléchargé.")
            val cancelled = synchronized(this) {
                if (job.cancelled.get() || downloads.none { it.key == key }) {
                    job.target.delete()
                    jobs.remove(key)
                    downloads.removeAll { it.key == key }
                    true
                } else {
                    val podcast = database.podcast(job.feedUrl) ?: error("Abonnement introuvable.")
                    database.savePodcast(
                        podcast.copy(episodes = podcast.episodes.map {
                            if (it.id == job.episodeId) it.copy(localPath = job.target.absolutePath) else it
                        }),
                        database.podcasts().indexOfFirst { it.feedUrl == job.feedUrl },
                    )
                    jobs.remove(key)
                    downloads.removeAll { it.key == key }
                    persistLocked()
                    false
                }
            }
            if (cancelled) {
                synchronized(this) { persistLocked() }
                notifyChanged()
                startNext()
                return
            }
            synchronized(this) {
                jobs.remove(key)
                downloads.removeAll { it.key == key }
            }
            notifyChanged()
            startNext()
        } catch (error: Exception) {
            if (job.cancelled.get()) {
                File("${job.target.absolutePath}.part").delete()
                finishCancelled(key)
                return
            }
            if (job.waitingForWifi.get()) {
                finishWaitingForWifi(key)
                return
            }
            if (job.paused.get()) {
                val index = synchronized(this) { downloads.indexOfFirst { it.key == key } }
                val item = synchronized(this) { downloads.getOrNull(index) }
                finishPaused(key, item?.done ?: 0, item?.total ?: 0)
                return
            }
            synchronized(this) {
                jobs.remove(key)
                val index = downloads.indexOfFirst { it.key == key }
                if (index >= 0) downloads[index] = downloads[index].copy(
                    status = DownloadStatus.ERROR,
                    error = error.localizedMessage ?: "Téléchargement impossible.",
                )
                persistLocked()
            }
            notifyChanged()
            startNext()
        } finally {
            connection?.disconnect()
        }
    }

    private fun finishPaused(key: String, done: Long, total: Long) {
        synchronized(this) {
            jobs.remove(key)
            val index = downloads.indexOfFirst { it.key == key }
            if (index >= 0) downloads[index] = downloads[index].copy(
                status = DownloadStatus.PAUSED,
                done = done,
                total = total,
            )
            persistLocked()
        }
        notifyChanged()
        startNext()
    }

    private fun finishCancelled(key: String) {
        synchronized(this) {
            jobs.remove(key)
            downloads.removeAll { it.key == key }
            persistLocked()
        }
        notifyChanged()
        startNext()
    }

    private fun finishWaitingForWifi(key: String) {
        synchronized(this) {
            jobs.remove(key)
            val index = downloads.indexOfFirst { it.key == key }
            if (index >= 0) {
                val item = downloads[index]
                val mayResume = !isMobileNetwork() ||
                    database.setting("downloads.mobile", "0") == "1" ||
                    item.mobileAllowed
                downloads[index] = item.copy(
                    status = if (mayResume) DownloadStatus.QUEUED else DownloadStatus.WAITING_FOR_WIFI,
                )
            }
            persistLocked()
        }
        notifyChanged()
        startNext()
    }

    private fun onNetworkChanged() {
        val onMobile = isMobileNetwork()
        val mobileAllowed = database.setting("downloads.mobile", "0") == "1"
        synchronized(this) {
            downloads.indices.forEach { index ->
                val item = downloads[index]
                if (onMobile && !mobileAllowed && !item.mobileAllowed) {
                    if (item.status == DownloadStatus.QUEUED) {
                        downloads[index] = item.copy(status = DownloadStatus.WAITING_FOR_WIFI)
                    } else if (item.status == DownloadStatus.DOWNLOADING) {
                        jobs[item.key]?.waitingForWifi?.set(true)
                    }
                } else {
                    if (item.status == DownloadStatus.WAITING_FOR_WIFI) {
                        downloads[index] = item.copy(status = DownloadStatus.QUEUED)
                    }
                    jobs[item.key]?.waitingForWifi?.set(false)
                }
            }
            persistLocked()
        }
        notifyChanged()
        startNext()
    }

    private fun isMobileNetwork(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    private fun publish(key: String, change: (PodcastDownload) -> PodcastDownload) {
        synchronized(this) {
            val index = downloads.indexOfFirst { it.key == key }
            if (index >= 0) {
                downloads[index] = change(downloads[index])
                val now = System.currentTimeMillis()
                if (now - lastProgressSaveAt >= 2_000) {
                    persistLocked()
                    lastProgressSaveAt = now
                }
            }
        }
        notifyChanged()
    }

    private fun persistLocked() {
        database.saveDownloadQueue(downloads.toList())
    }

    private fun destination(podcast: SavedPodcast, episode: SavedEpisode): File {
        val external = database.setting("storage.location", "internal") == "external"
        val base = if (external) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }
        val directory = File(base, "podcasts/${safeName(podcast.title)}")
        val extension = Regex("\\.(mp3|m4a|aac|ogg|opus|flac)(?:\\?.*)?$", RegexOption.IGNORE_CASE)
            .find(episode.remoteUrl)?.groupValues?.get(1) ?: "mp3"
        val name = safeName(episode.title).ifBlank { "episode" }.take(60)
        val hash = Integer.toUnsignedString(episode.id.hashCode(), 36)
        return File(directory, "$name-$hash.$extension")
    }

    private fun partialFile(feedUrl: String, episodeId: String): File? {
        val podcast = database.podcast(feedUrl) ?: return null
        val episode = podcast.episodes.firstOrNull { it.id == episodeId } ?: return null
        return File("${destination(podcast, episode).absolutePath}.part")
    }

    private fun deleteDownloadedFile(path: String) {
        if (path.startsWith("content://")) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path)) }
        } else {
            File(path).delete()
        }
    }

    private fun notifyChanged() {
        val state = synchronized(this) {
            val active = downloads.any {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
            }
            val startService = active && !serviceRequested
            val stopService = !active && serviceRequested
            if (startService) serviceRequested = true
            if (stopService) serviceRequested = false
            Pair(active, Triple(startService, stopService, downloads.toList()))
        }
        when {
            state.second.first -> startForegroundService()
            state.second.second -> {
                context.stopService(DownloadForegroundService.intent(context))
            }
            state.first && !DownloadForegroundService.updateIfRunning(state.second.third) -> {
                val shouldStart = synchronized(this) {
                    if (serviceRequested) false else {
                        serviceRequested = true
                        true
                    }
                }
                if (shouldStart) startForegroundService()
            }
        }
        mainHandler.post(onChanged)
    }

    private fun startForegroundService() {
        val intent = DownloadForegroundService.intent(context)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun key(feedUrl: String, episodeId: String) = "$feedUrl|$episodeId"

    private fun safeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class TransferJob(
        val url: String,
        val target: File,
        val paused: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val waitingForWifi: AtomicBoolean = AtomicBoolean(false),
        val feedUrl: String = "",
        val episodeId: String = "",
    )

    companion object {
        @Volatile
        private var instance: PodcastDownloadQueue? = null

        fun shared(
            context: Context,
            onChanged: (() -> Unit)? = null,
            serviceAlreadyStarted: Boolean = false,
        ): PodcastDownloadQueue {
            val queue = synchronized(this) {
                instance ?: PodcastDownloadQueue(
                    context.applicationContext,
                    LibraryDatabase(context.applicationContext),
                    serviceAlreadyStarted,
                ).also { instance = it }
            }
            if (onChanged != null) queue.setOnChanged(onChanged)
            return queue
        }
    }
}
