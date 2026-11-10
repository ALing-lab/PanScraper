package com.tamu.pan123.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType

@Composable
fun IosAlert(
    title: String,
    message: String,
    confirm: String = "确定",
    cancel: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Ios.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(horizontal = 52.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (c.dark) Color(0xFF2C2C2E) else Color(0xF2F2F2F7)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = IosType.headline, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
                Text(message, style = IosType.footnote, color = c.label, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight().pressable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Text(cancel, style = IosType.body, color = c.link) }
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.separator))
                    Box(
                        Modifier.weight(1f).fillMaxHeight().pressable(onClick = onConfirm),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            confirm,
                            style = IosType.headline,
                            color = if (destructive) c.red else c.link,
                        )
                    }
                }
            }
        }
    }
}
