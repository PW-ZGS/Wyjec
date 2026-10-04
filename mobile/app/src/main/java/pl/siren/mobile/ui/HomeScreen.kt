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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.siren.mobile.R
import pl.siren.mobile.core.Control
import pl.siren.mobile.core.SirenEngine
import pl.siren.mobile.core.UiState

/** Normal mode: own procedures, and RAISE ALARM for controllers. */
@Composable
fun HomeScreen(state: UiState, engine: SirenEngine, onSync: () -> Unit, onUnenroll: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var raising by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val profile = state.profile
    val raisable = profile?.controls?.filter { it.canRaise }.orEmpty()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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

            item { Section("Procedures") }
            state.scenarios.filter { it.groups.isNotEmpty() }.forEach { s ->
                item(key = "sc-" + s.versionId) {
                    var open by remember { mutableStateOf(false) }
                    Card(Modifier.padding(horizontal = 16.dp).clickable { open = !open }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = SirenColors.Muted)
                        }
                        if (open) {
                            s.groups.forEach { g ->
                                Spacer(Modifier.height(10.dp))
                                Text(g.location?.name ?: g.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                g.tasks.forEach { t -> Text("${t.orderNo}. ${t.description}", fontSize = 13.sp, color = SirenColors.Ink, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) }
                            }
                        }
                    }
                }
            }
            if (state.scenarios.isEmpty()) item { Text("Waiting for the first sync…", color = SirenColors.Muted, modifier = Modifier.padding(horizontal = 20.dp)) }
            item { Spacer(Modifier.height(24.dp)) }
        }

        if (raisable.isNotEmpty()) {
            Button(
                onClick = { raising = true },
                colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Red),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            ) {
                Icon(Icons.Default.NotificationsActive, null)
                Spacer(Modifier.width(8.dp))
                Text("RAISE ALARM", fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }

    if (raising) {
        RaiseSheet(raisable, state.canSign, onDismiss = { raising = false }) { c, description ->
            raising = false
            error = engine.raise(c, description)
            onSync()
        }
    }
    error?.let { AlertDialog(onDismissRequest = { error = null }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

/** Alarm type, optional short description, raise. Alarms are system-wide: no area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RaiseSheet(controls: List<Control>, canSign: Boolean, onDismiss: () -> Unit, onRaise: (Control, String) -> Unit) {
    var chosen by remember { mutableStateOf(controls.first()) }
    var description by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            controls.forEach { c ->
                Row(Modifier.fillMaxWidth().selectable(selected = c == chosen, onClick = { chosen = c }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = c == chosen, onClick = { chosen = c })
                    Text(c.name, fontSize = 16.sp, fontWeight = if (c == chosen) FontWeight.Bold else FontWeight.Normal)
                }
            }
            OutlinedTextField(
                value = description, onValueChange = { description = it.take(200) }, label = { Text("Description") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                onClick = { onRaise(chosen, description) }, enabled = canSign,
                colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Red),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
            ) { Text("RAISE ${chosen.name.uppercase()}", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
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
