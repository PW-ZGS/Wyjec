package pl.siren.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.siren.mobile.R

/** Enrolment: server address + this device's individually distributed credential. */
@Composable
fun SetupScreen(onEnroll: (serverUrl: String, deviceId: String, psk: String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("http://10.0.2.2:8080") }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var psk by rememberSaveable { mutableStateOf("") }
    var who by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(painterResource(R.drawable.siren_logo), contentDescription = "Siren", modifier = Modifier.height(96.dp).align(Alignment.CenterHorizontally))
        Text("Enroll this phone", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("WHERE TO GO. WHAT TO DO.", color = SirenColors.Red, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            "Scenarios and alarm keys are downloaded once and stored on the phone, so orders arrive even without network.",
            color = SirenColors.Muted,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Server address") }, singleLine = true,
            supportingText = { Text("Laptop on the same Wi-Fi: http://<laptop-ip>:8080 · emulator: http://10.0.2.2:8080") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
        )

        Text("Demo person", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        DEMO_PRESETS.forEach { p ->
            val selected = p.deviceId == deviceId
            OutlinedButton(
                onClick = { deviceId = p.deviceId; psk = p.psk; who = p.name },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) SirenColors.Navy else SirenColors.Line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(p.name, fontWeight = FontWeight.Bold, color = SirenColors.Ink)
                    Text(p.role, fontSize = 12.sp, color = SirenColors.Muted)
                }
            }
        }

        Text("Or enter a credential", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(value = deviceId, onValueChange = { deviceId = it; who = null }, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = psk, onValueChange = { psk = it; who = null }, label = { Text("Pre-shared key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { onEnroll(url.trim(), deviceId.trim(), psk) },
            enabled = url.isNotBlank() && deviceId.isNotBlank() && psk.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = SirenColors.Navy),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) { Text(if (who != null) "Enroll as $who" else "Enroll", fontSize = 16.sp) }
    }
}
