package io.github.bilidownload

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToInt

data class MusicTags(val title: String, val artist: String = "", val album: String = "", val cover: String = "")
data class AudioTrack(val id: String, val codec: String, val bitrate: Int, val language: String,
    val channels: Int, val duration: Double, val mixed: Boolean, val bytes: Long?, val audioBytes: Long?, val tags: MusicTags? = null, val container: String = "", val drc: Boolean = false) {
    val extension get() = when(codec) { "Opus" -> ".opus"; "FLAC" -> ".flac"; "MP3" -> ".mp3"; else -> ".m4a" }
    val label get() = listOf(when(codec) { "FLAC" -> "无损 FLAC"; "E-AC-3" -> "杜比 E-AC-3"; else -> codec }, if(drc) "动态压缩" else "", if(bitrate > 0) "$bitrate kb/s" else "", language,
        if(channels > 2) "$channels 声道" else "", MediaSize.format(bytes)).filter(String::isNotBlank).joinToString("  ")
}
data class AudioOutput(val track: AudioTrack, val mp3Kbps: Int = 0) {
    val extension get() = if(mp3Kbps == 0) track.extension else ".mp3"
    val estimatedBytes get() = if(mp3Kbps == 0) track.audioBytes else if(track.duration > 0) ceil(track.duration * mp3Kbps * 125).toLong() else null
    val summary get() = (if(track.mixed) "需下载整段视频  " else "") + track.label +
        if(track.mixed || mp3Kbps > 0) "  成品 " + MediaSize.format(estimatedBytes) else ""
    val needsProcessor get() = mp3Kbps > 0 || extension == ".flac" && track.container in setOf("audio/mp4","m4a")
    val mime get() = when(extension) { ".opus" -> "audio/ogg"; ".flac" -> "audio/flac"; ".mp3" -> "audio/mpeg"; else -> "audio/mp4" }
}
object AudioMedia {
    fun bili(data: JSONObject, cid: Long): List<Pair<AudioTrack, List<StreamPart>>> {
        val dash = data.optJSONObject("dash")
        val duration = dash?.optDouble("duration", 0.0)?.takeIf { it > 0 } ?: data.optLong("timelength") / 1000.0
        val sources = buildList {
            addAll(dash?.optJSONArray("audio").objects())
            for(key in listOf("flac", "dolby")) {
                val extra = dash?.optJSONObject(key)
                extra?.optJSONObject("audio")?.let(::add)
                addAll(extra?.optJSONArray("audio").objects())
            }
        }
        val tracks = sources.mapNotNull { raw ->
            val codec = when(val name = raw.optString("codecs").lowercase()) {
                "flac" -> "FLAC"; "ec-3","eac3" -> "E-AC-3"; "opus" -> "Opus"
                else -> if(name.startsWith("mp4a")) "AAC" else return@mapNotNull null
            }
            val url = raw.optString("baseUrl", raw.optString("base_url"))
            if(url.isBlank() || raw.optInt("drm_tech_type") != 0) return@mapNotNull null
            val id = "${raw.optInt("id")}-$codec"
            val bits = raw.optLong("bandwidth")
            val size = if(duration > 0 && bits > 0) ceil(duration * bits / 8).toLong() else null
            AudioTrack(id, codec, (bits/1000.0).roundToInt(), raw.optString("lang"), raw.optInt("channels"), duration, false, size, size, container = raw.optString("mimeType",raw.optString("mime_type","audio/mp4"))) to
                listOf(StreamPart("audio-$cid-$id", url, (raw.optJSONArray("backupUrl") ?: raw.optJSONArray("backup_url")).strings()))
        }.distinctBy { it.first.id }.sortedWith(compareByDescending<Pair<AudioTrack,List<StreamPart>>> { it.first.codec == "FLAC" }
            .thenByDescending { it.first.codec == "E-AC-3" }.thenByDescending { it.first.bitrate })
        if(tracks.isNotEmpty()) return tracks
        val rows = data.optJSONArray("durl").objects()
        if(rows.isEmpty()) return emptyList()
        val key = "mixed-${data.optInt("quality")}"
        val size = rows.map { it.optLong("size") }.let { if(it.all { n -> n > 0 }) it.sum() else null }
        return listOf(AudioTrack(key, "内置音轨", 0, "", 0, duration, true, size, null) to rows.mapIndexed { index, row ->
            StreamPart("audio-$cid-$key-$index", row.getString("url"), row.optJSONArray("backup_url").strings())
        })
    }
    fun youtube(info: JSONObject): List<AudioTrack> {
        val tags = MusicTags(info.optString("trackTitle").ifBlank { info.optString("title") }, info.optString("artist"), info.optString("album"), info.optString("cover"))
        return info.getJSONArray("variants").objects().map { row -> AudioTrack(row.getString("format"), row.getString("codec"),
            row.optInt("bitrate"), row.optString("language"), row.optInt("channels"), row.optDouble("duration", 0.0),
            row.getBoolean("mixed"), row.optLong("bytes").takeIf { it > 0 }, row.optLong("audioBytes").takeIf { it > 0 }, tags, row.optString("container"), row.optBoolean("drc")) }
    }
}
