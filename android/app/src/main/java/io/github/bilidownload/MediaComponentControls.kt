package io.github.bilidownload

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.bilidownload.ui.components.BadgeTone
import io.github.bilidownload.ui.components.SaltItemRow
import io.github.bilidownload.ui.components.SaltStatusBadge

@Composable
internal fun MediaComponentSetting(state: ComponentState, open: () -> Unit) {
    SaltItemRow(
        iconRes = R.drawable.ic_download,
        title = "YouTube / X 解析组件",
        subtitle = if (state.ready) "本地解析组件已启用" else "用于解析 YouTube 与 X 内容",
        trailing = {
            when {
                state.ready -> SaltStatusBadge("已就绪", BadgeTone.Success)
                state.checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> FilledTonalButton(
                    onClick = open,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        when {
                            state.downloading -> "查看进度"
                            state.error != null -> "重试下载"
                            else -> "下载组件"
                        },
                    )
                }
            }
        },
        onClick = if (!state.ready && !state.checking) open else null,
    )
}

@Composable
internal fun MediaComponentDialog(state: ComponentState, downloadBytes: Long, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val total = state.total?.takeIf { it > 0 } ?: downloadBytes
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = saltColors().card,
        properties = DialogProperties(dismissOnClickOutside = !state.downloading, dismissOnBackPress = !state.downloading),
        title = { Text("YouTube / X 解析组件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.downloading -> {
                        if (total > 0) {
                            val fraction = (state.bytes.toFloat() / total).coerceIn(0f, 1f)
                            Text(
                                if (state.bytes >= total) "正在校验组件…"
                                else Formatter.formatShortFileSize(context, state.bytes) + " / " + Formatter.formatShortFileSize(context, total),
                            )
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                trackColor = saltColors().subCard,
                            )
                        } else {
                            Text("已下载 " + Formatter.formatShortFileSize(context, state.bytes))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                trackColor = saltColors().subCard,
                            )
                        }
                    }
                    state.error != null -> Text(state.error, color = saltColors().error)
                    else -> Text("下载大小：" + Formatter.formatShortFileSize(context, downloadBytes))
                }
            }
        },
        confirmButton = {
            if (!state.downloading) {
                Button(onClick = onInstall, shape = RoundedCornerShape(10.dp)) {
                    Text(if (state.error == null) "下载" else "重试")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state.downloading) "取消下载" else "取消") }
        },
    )
}
