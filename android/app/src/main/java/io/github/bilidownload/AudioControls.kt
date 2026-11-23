package io.github.bilidownload

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

@Composable
fun ChoiceMenu(label: String, choices: List<String>, selected: Int, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), onClick = { open = true }) {
            Text("$label：" + choices.getOrElse(selected) { choices.first() })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, name -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; choose(i) }) }
        }
    }
}
