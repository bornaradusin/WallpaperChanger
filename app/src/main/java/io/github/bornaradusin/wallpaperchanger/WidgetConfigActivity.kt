package io.github.bornaradusin.wallpaperchanger

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Set up a widget: what it does, its text, the text colour and whether it shows the icon. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val start = WidgetUpdates.look(this, id)
        val colors = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD54F.toInt(), 0xFF81D4FA.toInt(), 0xFFA5D6A7.toInt(), 0xFFF48FB1.toInt())
        setContent {
            AppTheme {
                var action by remember { mutableStateOf(start.action) }
                var text by remember { mutableStateOf(start.text) }
                var color by remember { mutableStateOf(start.textColor) }
                var icon by remember { mutableStateOf(start.icon) }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Widget configuration") },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Action", style = MaterialTheme.typography.titleSmall)
                            WidgetAction.entries.forEach { a ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    if (text == action.label) text = a.label
                                    action = a
                                }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(action == a, null)
                                    Text(a.label, Modifier.padding(start = 8.dp))
                                }
                            }
                            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Widget text") }, singleLine = true)
                            Text("Text color", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                colors.forEach { c ->
                                    Box(
                                        Modifier.size(32.dp).background(Color(c), CircleShape)
                                            .border(if (c == color) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            .clickable { color = c },
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Widget icon", Modifier.weight(1f))
                                Switch(icon, { icon = it })
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            WidgetUpdates.save(this, id, WidgetLook(action, text, color, icon))
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }
}
