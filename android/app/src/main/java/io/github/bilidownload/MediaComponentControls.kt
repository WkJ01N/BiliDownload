package io.github.bilidownload

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun MediaComponentSetting(state: ComponentState, open: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("YouTube / X 解析组件", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        when {
            state.ready -> Text("已就绪", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> TextButton(onClick = open) {
                Text(when {
                    state.downloading -> "查看进度"
                    state.error != null -> "重试下载"
                    else -> "下载组件"
                })
            }
        }
    }
}

@Composable
internal fun MediaComponentDialog(state: ComponentState, downloadBytes: Long, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val total = state.total?.takeIf { it > 0 } ?: downloadBytes
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = !state.downloading, dismissOnBackPress = !state.downloading),
        title = { Text("YouTube / X 解析组件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.downloading -> {
                        if(total > 0) {
                            val fraction = (state.bytes.toFloat() / total).coerceIn(0f, 1f)
                            Text(if(state.bytes >= total) "正在校验组件…" else
                                Formatter.formatShortFileSize(context,state.bytes) + " / " + Formatter.formatShortFileSize(context,total))
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text("已下载 " + Formatter.formatShortFileSize(context, state.bytes))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    state.error != null -> Text(state.error)
                    else -> Text("下载大小：" + Formatter.formatShortFileSize(context, downloadBytes))
                }
            }
        },
        confirmButton = {
            if(!state.downloading) TextButton(onClick = onInstall) { Text(if(state.error == null) "下载" else "重试") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if(state.downloading) "取消下载" else "取消") }
        }
    )
}
