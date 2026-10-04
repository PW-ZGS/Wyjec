package pl.siren.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.siren.mobile.core.SirenEngine
import pl.siren.mobile.core.UiState
import pl.siren.mobile.data.LocalAlarm

/** Active alarm: what it is, what to do, where to go. Nothing else. */
@Composable
fun AlarmScreen(state: UiState, alarm: LocalAlarm, engine: SirenEngine) {
    val context = LocalContext.current
    val scenario = state.scenarios.firstOrNull { it.versionId == alarm.scenarioVersionId }
    val tasks = state.taskStates[alarm.instanceId].orEmpty()
    val canCancel = state.profile?.controls?.any { it.definitionId == alarm.definitionId && it.canCancel } == true
    var confirmCancel by remember { mutableStateOf(false) }
    var helpSent by rememberSaveable(alarm.instanceId) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(SirenColors.Red).padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text(alarm.title.uppercase(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
                alarm.description?.let { Text(it, color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (alarm.acknowledgedAt != null) {
                    Text("ACKNOWLEDGED", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (alarm.acknowledgedAt == null) {
                    Button(
                        onClick = { engine.acknowledge(alarm) },
                        colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Navy),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("ACKNOWLEDGE", fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                }

                if (scenario == null) Text("Procedure not synced", color = SirenColors.Muted)

                scenario?.groups?.forEach { g ->
                    Card {
                        Label("WHAT TO DO")
                        g.tasks.forEach { t ->
                            val done = tasks[t.id] == "DONE"
                            val toggle = { engine.setTask(alarm, t.id, if (done) "PENDING" else "DONE") }
                            Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = done, onCheckedChange = { toggle() },
                                    colors = CheckboxDefaults.colors(checkedColor = SirenColors.Green))
                                Text(t.description, fontSize = 15.sp, color = if (done) SirenColors.Muted else SirenColors.Ink,
                                    fontWeight = if (done) FontWeight.Normal else FontWeight.SemiBold)
                            }
                        }
                        g.location?.let { loc ->
                            Spacer(Modifier.height(14.dp))
                            Label("WHERE TO GO")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(loc.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                OutlinedButton(onClick = {
                                    val uri = Uri.parse("geo:${loc.lat},${loc.lon}?q=${loc.lat},${loc.lon}(${Uri.encode(loc.name)})")
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                }) {
                                    Icon(Icons.Default.Navigation, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("GO")
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { engine.needHelp(alarm); helpSent = true }, enabled = !helpSent,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(56.dp),
            ) { Text(if (helpSent) "HELP REQUESTED" else "NEED HELP", color = SirenColors.Red, fontWeight = FontWeight.Bold) }
            if (canCancel) {
                Button(
                    onClick = { confirmCancel = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Red),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(56.dp),
                ) { Text("END ALARM", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("End ${alarm.title}?") },
            confirmButton = { TextButton(onClick = { engine.cancel(alarm); confirmCancel = false }) { Text("END ALARM", color = SirenColors.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Back") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SirenColors.Red, letterSpacing = 1.sp)
}
