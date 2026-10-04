package pl.siren.mobile.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.siren.mobile.SirenApp
import pl.siren.mobile.service.SirenService

class MainActivity : ComponentActivity() {
    private val engine get() = (application as SirenApp).engine

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Restart so the service picks up the location foreground-service type if it was granted.
        if (engine.config.enrolled) { SirenService.stop(this); SirenService.start(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
        if (engine.config.enrolled) SirenService.start(this)

        setContent {
            SirenTheme {
                val state by engine.state.collectAsStateWithLifecycle()
                var openAlarm by rememberSaveable { mutableStateOf<String?>(null) }
                var dismissed by remember { mutableStateOf(setOf<String>()) }
                val autoOpen = state.activeAlarms.firstOrNull { it.instanceId !in dismissed }
                val shown = state.alarms.firstOrNull { it.instanceId == openAlarm } ?: autoOpen

                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) {
                        when {
                            !state.enrolled -> SetupScreen { url, id, psk ->
                                engine.enroll(url, id, psk)
                                SirenService.stop(this@MainActivity)
                                SirenService.start(this@MainActivity)
                            }
                            shown != null -> AlarmScreen(
                                state = state,
                                alarm = shown,
                                engine = engine,
                                onBack = { dismissed = dismissed + shown.instanceId; openAlarm = null },
                            )
                            else -> HomeScreen(
                                state = state,
                                engine = engine,
                                onOpenAlarm = { openAlarm = it },
                                onSync = { SirenService.syncNow(this@MainActivity) },
                                onUnenroll = { SirenService.stop(this@MainActivity); engine.unenroll() },
                            )
                        }
                    }
                }
            }
        }
    }
}
