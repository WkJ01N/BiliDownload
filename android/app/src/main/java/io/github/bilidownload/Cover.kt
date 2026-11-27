package io.github.bilidownload

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private object CoverCache {
    val memory = LruCache<String, ImageBitmap>(32)
}

private enum class CoverStatus { Loading, Loaded, Empty }

@Composable
fun Cover(
    url: String,
    modifier: Modifier = Modifier.fillMaxWidth().height(168.dp),
    cornerRadius: Dp = 12.dp,
) {
    var bitmap by remember(url) { mutableStateOf(if (url.isBlank()) null else CoverCache.memory.get(url)) }
    var status by remember(url) {
        mutableStateOf(
            when {
                url.isBlank() -> CoverStatus.Empty
                bitmap != null -> CoverStatus.Loaded
                else -> CoverStatus.Loading
            },
        )
    }

    LaunchedEffect(url) {
        if (url.isBlank()) {
            status = CoverStatus.Empty
            return@LaunchedEffect
        }
        val cached = CoverCache.memory.get(url)
        if (cached != null) {
            bitmap = cached
            status = CoverStatus.Loaded
            return@LaunchedEffect
        }
        status = CoverStatus.Loading
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val secure = if (url.startsWith("//")) "https:$url" else url.replaceFirst("http://", "https://")
                val uri = URI(secure)
                val conn = if (uri.host == "i.ytimg.com" || uri.host == "pbs.twimg.com") {
                    (uri.toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 8000
                        instanceFollowRedirects = false
                    }
                } else {
                    BiliApplication.api.connection(secure)
                }
                try {
                    conn.inputStream.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
        if (loaded != null) {
            CoverCache.memory.put(url, loaded)
            bitmap = loaded
            status = CoverStatus.Loaded
        } else {
            status = CoverStatus.Empty
        }
    }

    val salt = saltColors()
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(salt.subCard),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            CoverStatus.Loaded -> bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "视频封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            CoverStatus.Loading -> {
                val transition = rememberInfiniteTransition(label = "coverSkeleton")
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 0.55f,
                    animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
                    label = "skeletonAlpha",
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)),
                )
            }
            CoverStatus.Empty -> {
                Icon(
                    painter = painterResource(R.drawable.ic_video_placeholder),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
