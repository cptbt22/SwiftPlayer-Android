package com.swiftplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class SubtitleHelper(private val context: Context) {

    companion object {
        private val SUBTITLE_EXTENSIONS = mapOf(
            "srt" to MimeTypes.APPLICATION_SUBRIP,
            "ass" to MimeTypes.TEXT_SSA,
            "ssa" to MimeTypes.TEXT_SSA,
            "vtt" to MimeTypes.TEXT_VTT,
            "ttml" to MimeTypes.APPLICATION_TTML,
            "dfxp" to MimeTypes.APPLICATION_TTML
        )
    }

    fun findExternalSubtitles(videoUri: Uri): List<SubtitleFile> {
        val path = videoUri.path ?: return emptyList()
        val videoFile = File(path)
        if (!videoFile.exists()) return emptyList()
        val dir = videoFile.parentFile ?: return emptyList()
        val videoName = videoFile.nameWithoutExtension

        return dir.listFiles()
            ?.filter { file ->
                val ext = file.extension.lowercase()
                ext in SUBTITLE_EXTENSIONS && file.nameWithoutExtension.startsWith(videoName, ignoreCase = true)
            }
            ?.map { file ->
                val ext = file.extension.lowercase()
                SubtitleFile(
                    uri = Uri.fromFile(file),
                    mimeType = SUBTITLE_EXTENSIONS[ext] ?: MimeTypes.APPLICATION_SUBRIP,
                    language = extractLanguage(file.nameWithoutExtension, videoName),
                    label = file.name
                )
            }
            ?.sortedBy { it.label }
            ?: emptyList()
    }

    private fun extractLanguage(subtitleName: String, videoName: String): String {
        val suffix = subtitleName.removePrefix(videoName).removePrefix(".").removePrefix("_").removePrefix("-").trim()
        return suffix.ifEmpty { "默认" }
    }

    fun buildMediaItemWithSubtitles(videoUri: Uri, title: String, subtitles: List<SubtitleFile>): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(videoUri)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())

        if (subtitles.isNotEmpty()) {
            val configs = subtitles.map { sub ->
                MediaItem.SubtitleConfiguration.Builder(sub.uri)
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.language)
                    .setLabel(sub.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }
            builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    fun getSubtitleTracks(player: ExoPlayer): List<SubtitleTrack> {
        val tracks = mutableListOf<SubtitleTrack>()
        for (group in player.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    tracks.add(SubtitleTrack(
                        group = group,
                        trackIndex = i,
                        language = format.language ?: "未知",
                        label = format.label ?: format.language ?: "字幕 ${i + 1}",
                        isSelected = group.isTrackSelected(i)
                    ))
                }
            }
        }
        return tracks
    }

    fun selectSubtitleTrack(player: ExoPlayer, track: SubtitleTrack?) {
        for (group in player.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                if (track == null) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                } else {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(track.trackIndex)))
                        .build()
                }
                break
            }
        }
    }
}

data class SubtitleFile(val uri: Uri, val mimeType: String, val language: String, val label: String)
data class SubtitleTrack(val group: androidx.media3.common.Tracks.Group, val trackIndex: Int, val language: String, val label: String, val isSelected: Boolean)
