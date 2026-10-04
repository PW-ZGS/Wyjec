package pl.siren.mobile.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.siren.mobile.SirenApp

/**
 * Keeps the phone on standby: polls the server, listens on the mesh, flushes the status outbox and
 * reports position while an alarm is active.
 */
class SirenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine get() = (application as SirenApp).engine
    private var locating = false

    private val locationListener = LocationListener { loc: Location ->
        engine.reportPosition(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The location type is allowed only once the user granted location access.
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = when {
            Build.VERSION.SDK_INT < 29 -> 0
            hasLocation -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, engine.alerts.standbyNotification("Listening for alarms"), type)
        engine.startMesh()
        scope.launch {
            var first = true
            while (isActive) {
                engine.tick(forceSync = first)
                first = false
                updateLocationUpdates()
                delay(POLL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SYNC) scope.launch { engine.tick(forceSync = true) }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationUpdates() {
        val want = engine.state.value.activeAlarms.isNotEmpty() && engine.state.value.profile?.mayReportPosition == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (want == locating) return
        val lm = getSystemService(LocationManager::class.java)
        Looper.getMainLooper().let { looper ->
            if (want) {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }.forEach {
                    lm.requestLocationUpdates(it, 10_000L, 10f, locationListener, looper)
                    lm.getLastKnownLocation(it)?.let(locationListener::onLocationChanged)
                }
            } else {
                lm.removeUpdates(locationListener)
            }
        }
        locating = want
    }

    override fun onDestroy() {
        scope.cancel()
        engine.stopMesh()
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(locationListener) }
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 3_000L
        private const val ACTION_SYNC = "pl.siren.mobile.SYNC"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SirenService::class.java))

        fun syncNow(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, SirenService::class.java).setAction(ACTION_SYNC))

        fun stop(context: Context) = context.stopService(Intent(context, SirenService::class.java))
    }
}
