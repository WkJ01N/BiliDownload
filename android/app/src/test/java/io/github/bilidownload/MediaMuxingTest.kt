package io.github.bilidownload

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream

class MediaMuxingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recognizesOnlyCompleteNonFragmentedMp4() {
        val fixture = folder.newFile()
        fun write(vararg types: String) = DataOutputStream(fixture.outputStream()).use { out ->
            for (type in types) {
                out.writeInt(12)
                out.writeBytes(type)
                out.writeInt(0)
            }
        }
        write("ftyp", "moov", "mdat")
        assertTrue(isCompleteMp4(fixture))
        write("ftyp", "moov", "moof", "mdat")
        assertFalse(isCompleteMp4(fixture))
        write("ftyp", "mdat")
        assertFalse(isCompleteMp4(fixture))
        write("ftyp", "moov", "mdat")
        fixture.writeBytes(fixture.readBytes().dropLast(2).toByteArray())
        assertFalse(isCompleteMp4(fixture))
    }
}
