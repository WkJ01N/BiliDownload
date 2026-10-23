package io.github.bilidownload

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun Cover(url: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if(url.isBlank()) return@runCatching null
                val secure = if(url.startsWith("//")) "https:$url" else url.replaceFirst("http://", "https://")
                val conn = BiliApplication.api.connection(secure)
                try { conn.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } }
                finally { conn.disconnect() }
            }.getOrNull()
        }
    }
    bitmap?.let { Image(it, contentDescription = "视频封面", modifier = Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop) }
}
