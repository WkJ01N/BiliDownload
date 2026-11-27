package io.github.bilidownload

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.bilidownload.ui.components.SaltCard
import io.github.bilidownload.ui.components.SaltCheckbox
import io.github.bilidownload.ui.components.SaltTextField

@Composable
fun BiliTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) = SaltTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = "",
    modifier = modifier,
    label = label,
    minLines = minLines,
    maxLines = maxLines,
)

@Composable
fun BiliCard(content: @Composable ColumnScope.() -> Unit) = SaltCard(content = content)

@Composable
fun BiliCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) =
    SaltCheckbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
