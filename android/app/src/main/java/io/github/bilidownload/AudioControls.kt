package io.github.bilidownload

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

@Composable
fun ChoiceMenu(label: String, choices: List<String>, selected: Int, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            border = BorderStroke(1.dp, saltColors().subtleBorder),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = saltColors().subCard),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            onClick = { open = true },
        ) {
            Text(
                text = "$label：" + choices.getOrElse(selected) { choices.first() },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        choose(i)
                    },
                )
            }
        }
    }
}
