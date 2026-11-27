package io.github.bilidownload

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.bilidownload.ui.components.SaltOutlinedButton

@Composable
fun ChoiceMenu(label: String, choices: List<String>, selected: Int, choose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (open) 270f else 90f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 480f),
        label = "choiceArrow",
    )
    Box {
        SaltOutlinedButton(
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
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(arrowRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
