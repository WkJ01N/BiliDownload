package io.github.bilidownload
import org.json.JSONObject
import java.util.Locale
object MediaSize {
    fun estimate(data: JSONObject, video: JSONObject): Long? {
        val dash = data.getJSONObject("dash")
        val duration = dash.optDouble("duration", data.optDouble("timelength", 0.0) / 1000.0)
        val bandwidth = video.optLong("bandwidth")
        var audioBandwidth = 0L
        if(bandwidth <= 0 || duration <= 0 || !duration.isFinite()) return null
        val audio = dash.optJSONArray("audio").objects()
        if(audio.isNotEmpty()) {
            val aac = audio.filter { it.optString("codecs").startsWith("mp4a") }.maxOfOrNull { it.optLong("bandwidth") } ?: return null
            if(aac <= 0) return null
            audioBandwidth = aac
        }
        return fromBitrates(duration, bandwidth, audioBandwidth)
    }
    fun fromBitrates(duration: Double, video: Long, audio: Long = 0): Long? {
        if(!duration.isFinite() || duration <= 0 || video <= 0 || audio < 0) return null
        val size = duration * (video.toDouble() + audio) / 8
        return if(size > 0 && size < Long.MAX_VALUE) kotlin.math.ceil(size).toLong() else null
    }
    fun format(bytes: Long?): String = if(bytes == null || bytes <= 0) "大小未知" else if(bytes >= 1073741824) String.format(Locale.getDefault(), "约 %.2f GiB", bytes / 1073741824.0) else String.format(Locale.getDefault(), "约 %.1f MiB", bytes / 1048576.0)
}
