package io.github.bilidownload
import org.junit.Assert.*
import org.junit.Test
class MediaSizeTest {
    @Test fun computesVideoAndAudioAndRejectsMissingMetadata() {
        assertEquals(12400000L, MediaSize.fromBitrates(100.0,800000,192000))
        assertEquals(7400000L, MediaSize.fromBitrates(100.0,400000,192000))
        assertNull(MediaSize.fromBitrates(0.0,800000))
        assertNull(MediaSize.fromBitrates(100.0,0))
        assertNull(MediaSize.fromBitrates(Double.NaN,800000))
    }
    @Test fun formatsMissingAndKnownSizes() {
        assertEquals("大小未知", MediaSize.format(null))
        assertEquals("大小未知", MediaSize.format(0))
        assertTrue(MediaSize.format(1048576).contains("MiB"))
        assertTrue(MediaSize.format(1073741824).contains("GiB"))
    }
}
