package com.example.oxigenplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.oxigenplayer.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Disponibil") },
        text = { Text("O nouă versiune (${updateInfo.versionCode}) este disponibilă. Doriți să o instalați?") },
        confirmButton = {
            Button(onClick = onUpdate) {
                Text("Instalează")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Mai târziu")
            }
        }
    )
}
