package pl.siren.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.siren.mobile.core.SirenEngine
import pl.siren.mobile.core.UiState
import pl.siren.mobile.data.LocalAlarm
import pl.siren.mobile.data.Task
import java.text.SimpleDateFormat
import java.util.Date

/** On alarm the app turns into an interactive to-do list: where to go, what to do. */
@Composable
fun AlarmScreen(state: UiState, alarm: LocalAlarm, engine: SirenEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val scenario = state.scenarios.firstOrNull { it.versionId == alarm.scenarioVersionId }
    val tasks = state.taskStates[alarm.instanceId].orEmpty()
    val canCancel = state.profile?.controls?.any { it.definitionId == alarm.definitionId && it.canCancel } == true
    var confirmCancel by remember { mutableStateOf(false) }
    val headColor = if (alarm.active) SirenColors.Red else SirenColors.Green
    val allTasks = scenario?.groups?.flatMap { it.tasks }.orEmpty()
    val done = allTasks.count { tasks[it.id] == "DONE" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(headColor).padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 22.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(if (alarm.active) "ALARM" else "ALL CLEAR", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(alarm.title.uppercase(), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Raised ${SimpleDateFormat("HH:mm:ss").format(Date(alarm.raisedAt))} · received via ${alarm.viaBearer.lowercase()}" +
                        (if (alarm.hopCount > 0) " · ${alarm.hopCount} hops" else "") + " · signature verified",
                    color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
                )
                if (allTasks.isNotEmpty()) Text("$done of ${allTasks.size} tasks done", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (alarm.active && alarm.acknowledgedAt == null) {
                Button(
                    onClick = { engine.acknowledge(alarm) },
                    colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Navy),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(60.dp),
                ) { Text("I've got it — confirm receipt", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }

            if (scenario == null) {
                Card { Text("This alarm's scenario version is not stored on this phone. Sync when possible.", color = SirenColors.Muted) }
            } else if (scenario.groups.isEmpty()) {
                Card { Text("You have no tasks in this scenario.", color = SirenColors.Muted) }
            }

            scenario?.groups?.forEach { g ->
                Card(color = Color.White, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(SirenColors.Soft).padding(1.dp)) {
                    Text("WHERE TO GO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SirenColors.Red, letterSpacing = 1.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.location?.name ?: g.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            g.location?.address?.let { Text(it, color = SirenColors.Muted, fontSize = 13.sp) }
                            Text("Role: ${g.name}", color = SirenColors.Muted, fontSize = 13.sp)
                        }
                        g.location?.let { loc ->
                            OutlinedButton(onClick = {
                                val uri = Uri.parse("geo:${loc.lat},${loc.lon}?q=${loc.lat},${loc.lon}(${Uri.encode(loc.name)})")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }) {
                                Icon(Icons.Default.Navigation, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Go")
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("WHAT TO DO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SirenColors.Red, letterSpacing = 1.sp)
                    g.tasks.forEachIndexed { i, t ->
                        TaskRow(t, tasks[t.id] ?: "PENDING", first = i == 0, enabled = alarm.active) { engine.setTask(alarm, t.id, it) }
                    }
                }
            }

            if (canCancel && alarm.active) {
                OutlinedButton(onClick = { confirmCancel = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Cancel alarm (controller)", color = SirenColors.Red, fontWeight = FontWeight.Bold)
                }
            }
            Text("Instance ${alarm.instanceId.take(8)} · scenario v${scenario?.version ?: "?"}", fontSize = 11.sp, color = SirenColors.Muted)
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel ${alarm.title}?") },
            text = { Text("A signed cancel is sent to everyone. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { engine.cancel(alarm); confirmCancel = false }) { Text("CANCEL ALARM", color = SirenColors.Red, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Back") } },
        )
    }
}

@Composable
private fun TaskRow(task: Task, state: String, first: Boolean, enabled: Boolean, onState: (String) -> Unit) {
    val (label, fg, bg) = when (state) {
        "DONE" -> Triple("Done", SirenColors.Green, SirenColors.GreenSoft)
        "IN_PROGRESS" -> Triple("In progress", SirenColors.Amber, SirenColors.AmberSoft)
        "EN_ROUTE" -> Triple("En route", SirenColors.Blue, SirenColors.BlueSoft)
        "BLOCKED" -> Triple("Blocked", SirenColors.Red, SirenColors.RedSoft)
        else -> Triple("To do", SirenColors.Muted, Color.White)
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state == "DONE") Icon(Icons.Default.CheckCircle, null, tint = SirenColors.Green) else Text("${task.orderNo}", fontWeight = FontWeight.Bold, color = SirenColors.Muted, modifier = Modifier.width(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(task.description, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = if (state == "DONE") FontWeight.Normal else FontWeight.SemiBold,
                color = if (state == "DONE") SirenColors.Muted else SirenColors.Ink)
            Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        if (task.requiresConfirmation && state != "DONE") Text("Report back when done", fontSize = 11.sp, color = SirenColors.Amber, modifier = Modifier.padding(start = 32.dp, top = 2.dp))
        if (enabled && state != "DONE") {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state == "PENDING" || state == "BLOCKED") {
                    SmallButton(if (first) "On my way" else "Start", SirenColors.Navy) { onState(if (first) "EN_ROUTE" else "IN_PROGRESS") }
                }
                SmallButton("Done", SirenColors.Green) { onState("DONE") }
                if (state != "BLOCKED") SmallButton("Blocked", SirenColors.Red, outlined = true) { onState("BLOCKED") }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, color: Color, outlined: Boolean = false, onClick: () -> Unit) {
    if (outlined) {
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp)) { Text(text, color = color) }
    } else {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color), shape = RoundedCornerShape(10.dp)) { Text(text) }
    }
}
