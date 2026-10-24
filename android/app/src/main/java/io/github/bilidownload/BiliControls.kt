package io.github.bilidownload

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable fun BiliTextField(value: String, onValueChange: (String) -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { label() }
        OutlinedTextField(value, onValueChange, minLines = minLines, maxLines = maxLines,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .border(0.5.dp, if(focused) radixFocusColor() else colors.outline, RoundedCornerShape(4.dp)),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent))
    }
}

@Composable fun BiliCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), content = content)
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable fun BiliCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).background(if(checked) colors.primary else colors.surface, RoundedCornerShape(3.dp))
            .border(0.5.dp, if(checked) colors.primary else colors.outline, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
            if(checked) Icon(painterResource(R.drawable.ic_complete), null, Modifier.size(14.dp), tint = colors.onPrimary)
        }
    }
}

@Composable fun BiliSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Switch(checked, onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = colors.onPrimary,
        checkedTrackColor = colors.primary, checkedBorderColor = Color.Transparent, uncheckedTrackColor = colors.surfaceVariant,
        uncheckedThumbColor = colors.onSurfaceVariant, uncheckedBorderColor = Color.Transparent))
}
