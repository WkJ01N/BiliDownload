package io.github.bilidownload

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class AudioTest {
    @Test fun highestTrackAndIndependentTransfer() {
        val payload = JSONObject("""{"dash":{"duration":10,"video":[{"baseUrl":"forbidden-video"}],"audio":[{"id":1,"codecs":"mp4a.40.2","bandwidth":96000,"baseUrl":"aac"}],"flac":{"audio":{"id":2,"codecs":"fLaC","baseUrl":"flac"}},"dolby":{"audio":[{"id":3,"codecs":"ec-3","baseUrl":"dolby"}]}}}""")
        val choices = AudioMedia.bili(payload,10)
        assertEquals(listOf("FLAC","E-AC-3","AAC"),choices.map { it.first.codec })
        assertEquals(listOf("flac","dolby","aac"),choices.flatMap { it.second }.map { it.url })
        assertEquals(120000L,choices.last().first.bytes)
        for(rate in listOf(128,192,320)) assertEquals(rate*1250L,AudioOutput(choices.first().first,rate).estimatedBytes)
    }
    @Test fun mixedSourceAndNames() {
        val track = AudioMedia.bili(JSONObject("""{"quality":64,"durl":[{"url":"a","size":100},{"url":"b","size":20}]}"""),9).single().first
        assertTrue(track.mixed); assertEquals(120L,track.bytes); assertNull(track.audioBytes)
        assertEquals("音轨 (1).flac",FileNaming.candidate("音轨.flac",1))
        assertEquals("音乐.opus",FileNaming.normalize("音乐.opus",".opus"))
        assertTrue(YoutubeLink.parse("https://music.youtube.com/playlist?list=PL123")!!.music)
    }
}
