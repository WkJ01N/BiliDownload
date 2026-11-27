package io.github.bilidownload

import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class XStorageTest {
    @Test fun archiveAndClassifiedMediaArePublishedWithActualNames(): Unit = runBlocking(Dispatchers.IO) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver
        val id = UUID.randomUUID().toString()
        val x = XContent(id,"qa","photo","image",".png",null,emptyList(),false,0.0)
        val src = File.createTempFile("photo",".png",ctx.cacheDir)
        val published = mutableListOf<Uri>()
        try {
            src.writeBytes(byteArrayOf(1,2,3))
            for(grouped in listOf(true,false)) {
                val folder = if(grouped) "Download/BiliDownload/qa-$id/" else "Pictures/BiliDownload/"
                val names = mutableListOf<String>()
                repeat(2) {
                    val uri = BiliApplication.engine.publish(src,"QA-$id.png","image/png",XDestination(grouped,null),x)
                    published.add(uri)
                    resolver.query(uri,arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE),null,null,null)!!.use { c ->
                        assertTrue(c.moveToFirst()); assertEquals(folder,c.getString(0)); names.add(c.getString(1)); assertEquals("image/png",c.getString(2))
                    }
                }
                assertNotEquals(names[0],names[1])
            }
        } finally {
            published.forEach { resolver.delete(it,null,null) }
            src.delete()
        }
    }
}
