package pl.siren.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.siren.mobile.R
import pl.siren.mobile.core.Control
import pl.siren.mobile.core.SirenEngine
import pl.siren.mobile.core.UiState
import java.text.SimpleDateFormat
import java.util.Date

/** Normal mode: standby status, the pocket library of procedures, and alarm controls for controllers. */
@Composable
fun HomeScreen(state: UiState, engine: SirenEngine, onOpenAlarm: (String) -> Unit, onSync: () -> Unit, onUnenroll: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Control?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val profile = state.profile

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.siren_logo), null, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile?.personName ?: "Siren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(listOfNotNull(profile?.role, profile?.organizationName).joinToString(" · "), fontSize = 12.sp, color = SirenColors.Muted)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Sync now") }, onClick = { menu = false; onSync() })
                        DropdownMenuItem(text = { Text("Unenroll this phone") }, onClick = { menu = false; onUnenroll() })
                    }
                }
            }
        }

        item {
            Card(Modifier.padding(horizontal = 16.dp), color = SirenColors.Navy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (state.online) SirenColors.Green else SirenColors.Amber))
                    Spacer(Modifier.width(10.dp))
                    Text(if (state.online) "Standing by — online" else "Standing by — offline", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.online) "Alarms arrive from the server and from nearby phones."
                    else "Server unreachable. Alarms still arrive from nearby phones; status is queued.",
                    color = Color(0xFFCAD3E0), fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Stat("${state.scenarios.size}", "scenarios")
                    Stat("${state.keys}", "alarm keys")
                    Stat(if (state.relayMode == "RELAY") "on" else "off", "mesh relay")
                    Stat("${state.outbox}", "queued")
                }
                Spacer(Modifier.height(10.dp))
                Text("Last sync ${if (state.lastSyncAt == 0L) "never" else SimpleDateFormat("HH:mm:ss").format(Date(state.lastSyncAt))}", color = Color(0xFF8E9AAD), fontSize = 12.sp)
            }
        }

        val raisable = profile?.controls?.filter { it.canRaise }.orEmpty()
        if (raisable.isNotEmpty()) {
            item { Section("Raise alarm") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You are an authorized controller. The alarm is signed on this phone and spreads even without the server.", fontSize = 13.sp, color = SirenColors.Muted)
                    raisable.forEach { c ->
                        val running = state.activeAlarms.any { it.definitionId == c.definitionId }
                        Button(
                            onClick = { confirm = c }, enabled = !running && state.canSign,
                            colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Red),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Default.NotificationsActive, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (running) "${c.name} — active" else "Raise ${c.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (state.alarms.isNotEmpty()) {
            item { Section("Recent alarms") }
            state.alarms.take(5).forEach { a ->
                item(key = "alarm-" + a.instanceId) {
                    Card(Modifier.padding(horizontal = 16.dp).clickable { onOpenAlarm(a.instanceId) }, color = if (a.active) SirenColors.RedSoft else SirenColors.Soft) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.title, fontWeight = FontWeight.Bold)
                                Text("${SimpleDateFormat("dd MMM HH:mm").format(Date(a.raisedAt))} · via ${a.viaBearer.lowercase()}${if (a.hopCount > 0) " · ${a.hopCount} hops" else ""}",
                                    fontSize = 12.sp, color = SirenColors.Muted)
                            }
                            Pill(if (a.active) "ACTIVE" else "ENDED", if (a.active) SirenColors.Red else SirenColors.Muted)
                        }
                    }
                }
            }
        }

        item { Section("Procedures — stored offline") }
        state.scenarios.forEach { s ->
            item(key = "sc-" + s.versionId) {
                var open by remember { mutableStateOf(false) }
                Card(Modifier.padding(horizontal = 16.dp).clickable { open = !open }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("${s.code} · v${s.version} · ${s.groups.sumOf { it.tasks.size }} tasks for you", fontSize = 12.sp, color = SirenColors.Muted)
                        }
                        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = SirenColors.Muted)
                    }
                    if (open) {
                        if (s.groups.isEmpty()) Text("No tasks assigned to you — you control this alarm.", fontSize = 13.sp, color = SirenColors.Muted, modifier = Modifier.padding(top = 8.dp))
                        s.groups.forEach { g ->
                            Spacer(Modifier.height(10.dp))
                            Text("${g.name} — ${g.location?.name ?: ""}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            g.tasks.forEach { t -> Text("${t.orderNo}. ${t.description}", fontSize = 13.sp, color = SirenColors.Ink, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) }
                        }
                        Text("Bundle sha256 ${pl.siren.mobile.crypto.Crypto.hex(s.sha256).take(16)}…", fontSize = 10.sp, color = SirenColors.Muted,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
        if (state.scenarios.isEmpty()) item { Text("Waiting for the first sync…", color = SirenColors.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }

        if (state.log.isNotEmpty()) {
            item { Section("Activity") }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    state.log.take(12).forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SirenColors.Muted) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Raise ${c.name}?") },
            text = { Text("Every assigned person receives orders immediately — over the server and over nearby phones.") },
            confirmButton = {
                TextButton(onClick = { error = engine.raise(c, null); confirm = null; onSync() }) { Text("RAISE", color = SirenColors.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Back") } },
        )
    }
    error?.let { AlertDialog(onDismissRequest = { error = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = SirenColors.Soft, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color).padding(16.dp)) { content() }
}

@Composable
fun Section(title: String) {
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SirenColors.Muted, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 20.dp, top = 8.dp))
}

@Composable
fun Pill(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = Color(0xFF8E9AAD), fontSize = 11.sp)
    }
}
