package org.nativescript.audiobookplayer

data class SavedEpisode(
    val id: String,
    val title: String,
    val remoteUrl: String,
    val localPath: String? = null,
    val description: String = "",
    val publishedAt: String = "",
    val duration: String = "",
    val artwork: String? = null,
)

data class SavedPodcast(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artwork: String? = null,
    val episodes: List<SavedEpisode>,
    val description: String = "",
)
