package pl.siren.mobile.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import pl.siren.mobile.crypto.Crypto
import pl.siren.mobile.data.Area
import pl.siren.mobile.data.Config
import pl.siren.mobile.data.LocalAlarm
import pl.siren.mobile.data.LocalKey
import pl.siren.mobile.data.LocalScenario
import pl.siren.mobile.data.LocalStore
import pl.siren.mobile.mesh.MeshRelay
import pl.siren.mobile.net.HttpException
import pl.siren.mobile.net.ServerApi
import java.io.IOException
import java.time.Instant
import java.util.UUID

data class Control(val definitionId: String, val code: String, val name: String, val canRaise: Boolean, val canCancel: Boolean)

data class Profile(
    val personName: String,
    val role: String?,
    val organizationName: String?,
    val domain: String?,
    val deviceClass: String,
    val mayReportPosition: Boolean,
    val controls: List<Control>,
)

data class UiState(
    val enrolled: Boolean = false,
    val profile: Profile? = null,
    val online: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    val scenarios: List<LocalScenario> = emptyList(),
    val alarms: List<LocalAlarm> = emptyList(),
    val taskStates: Map<String, Map<String, String>> = emptyMap(),
    val outbox: Int = 0,
    val relayMode: String = "RECEIVE_ONLY",
    val keys: Int = 0,
    val canSign: Boolean = false,
    val log: List<String> = emptyList(),
) {
    val activeAlarms get() = alarms.filter { it.active }
}

/**
 * Device-side core. Accepts signed alarms from any path (server poll, mesh), verifies them with the
 * locally stored public keys, de-duplicates, relays, and keeps a store-and-forward status outbox.
 * Works offline from the last synced scenarios and keys.
 */
class SirenEngine(context: Context) {
    val config = Config(context)
    val store = LocalStore(context)
    val alerts = Alerts(context)
    private val api = ServerApi(config)
    private val mesh = MeshRelay(context) { p, s, hop, from -> onMesh(p, s, hop, from.hostAddress ?: "?") }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val log = ArrayDeque<String>()

    init {
        refresh()
    }

    // ───────────── lifecycle ─────────────

    fun startMesh() = runCatching { mesh.start() }.onFailure { note("Mesh unavailable: ${it.message}") }

    fun stopMesh() = mesh.stop()

    fun enroll(serverUrl: String, deviceId: String, psk: String) {
        config.clear()
        store.wipe()
        config.serverUrl = serverUrl
        config.deviceId = deviceId
        config.psk = psk
        log.clear()
        refresh()
    }

    fun unenroll() {
        alerts.stopSound()
        config.clear()
        store.wipe()
        log.clear()
        refresh()
    }

    /** One service-loop iteration: sync when due, then poll, bridge and flush. */
    fun tick(forceSync: Boolean = false) {
        if (!config.enrolled) return
        try {
            if (forceSync || System.currentTimeMillis() - config.lastSyncAt > SYNC_EVERY_MS) sync()
            poll()
            uploadPending()
            flushOutbox()
            setOnline(true, null)
        } catch (e: HttpException) {
            if (e.serverDown) setOnline(false, "Offline — working from stored scenarios")
            else setOnline(e.code != 401, "Server refused: ${e.message}")
        } catch (e: IOException) {
            setOnline(false, "Offline — working from stored scenarios")
        } catch (e: Exception) {
            Log.e(TAG, "tick", e)
            setOnline(false, e.message)
        }
    }

    // ───────────── Server–App channel ─────────────

    private fun sync() {
        val r = api.sync()
        val keys = r.getJSONArray("keys").objects().map { k ->
            LocalKey(
                epochId = k.getInt("keyEpochId"),
                organizationId = k.getString("organizationId"),
                publicKey = Crypto.unb64(k.getString("publicKey")),
                signingKey = k.optString("privateKey").takeIf { it.isNotBlank() && it != "null" }?.let(Crypto::unb64),
                validFrom = Instant.parse(k.getString("validFrom")).toEpochMilli(),
                validTo = Instant.parse(k.getString("validTo")).toEpochMilli(),
                status = k.getString("status"),
            )
        }
        val scenarios = r.getJSONArray("scenarios").objects().mapNotNull { s ->
            val bundle = Crypto.unb64(s.getString("bundle"))
            val sha = Crypto.sha256(bundle)
            if (Crypto.hex(sha) != s.getString("bundleSha256")) {
                note("Scenario ${s.getString("alarmCode")} failed integrity check — ignored")
                return@mapNotNull null
            }
            LocalScenario(s.getString("scenarioVersionId"), s.getString("alarmDefinitionId"), s.getString("alarmCode"),
                s.getString("alarmName"), s.getInt("version"), bundle, sha)
        }
        val policy = r.getJSONObject("policy")
        store.replaceKeys(keys)
        store.replaceScenarios(scenarios)
        store.savePolicy(policy.getString("relayMode"), policy.getJSONArray("allowedBearers").strings(), Crypto.unb64(policy.getString("signedByServer")))
        val person = r.optJSONObject("person")
        val device = r.getJSONObject("device")
        config.profileJson = JSONObject()
            .put("personName", person?.optString("displayName") ?: "Device")
            .put("role", person?.optString("role"))
            .put("organizationName", person?.optString("organizationName"))
            .put("domain", person?.optString("domain"))
            .put("deviceClass", device.getString("deviceClass"))
            .put("mayReportPosition", device.getBoolean("mayReportPosition"))
            .put("controls", r.getJSONArray("controls"))
            .toString()
        if (config.lastSyncAt == 0L) note("Enrolled. ${scenarios.size} scenarios and ${keys.size} alarm keys stored offline.")
        config.lastSyncAt = System.currentTimeMillis()
        refresh()
    }

    private fun poll() {
        val r = api.alarms(config.feedCursor)
        r.getJSONArray("alarms").objects().forEach { a ->
            handleSigned(a.getString("payload"), a.getString("signature"), "INTERNET", 0, fromServer = true)
        }
        config.feedCursor = Instant.parse(r.getString("serverTime")).toEpochMilli() - 5_000
    }

    /** Bridge: alarms that arrived over the mesh (or were raised here) are also handed to the server. */
    private fun uploadPending() {
        for (seen in store.notUploaded()) {
            try {
                val res = api.submitAlarm(Crypto.b64(seen.payload), Crypto.b64(seen.signature), seen.firstBearer, seen.hopCount)
                store.markUploaded(seen.eventId)
                if (res.optString("outcome") == "REJECTED") note("Server rejected alarm: ${res.optString("reason")}")
            } catch (e: HttpException) {
                if (e.serverDown) throw e
                store.markUploaded(seen.eventId)
                note("Server refused alarm upload: ${e.message}")
            }
        }
    }

    private fun flushOutbox() {
        val items = store.outbox()
        if (items.isEmpty()) return
        val batch = JSONObject().put("receptions", JSONArray()).put("tasks", JSONArray()).put("positions", JSONArray())
        items.forEach { item ->
            val key = when (item.kind) { "RECEPTION" -> "receptions"; "TASK" -> "tasks"; else -> "positions" }
            batch.getJSONArray(key).put(item.payload)
        }
        try {
            api.status(batch)
            store.removeOutbox(items.map { it.id })
        } catch (e: HttpException) {
            if (e.serverDown) { store.bumpOutbox(items.map { it.id }); throw e }
            // Bad data will never be accepted: drop it instead of retrying forever.
            store.removeOutbox(items.map { it.id })
            note("Status rejected by server: ${e.message}")
        } catch (e: IOException) {
            store.bumpOutbox(items.map { it.id })
            throw e
        }
        refresh()
    }

    // ───────────── alarms ─────────────

    private fun onMesh(payloadB64: String, signatureB64: String, hop: Int, from: String) {
        handleSigned(payloadB64, signatureB64, "WIFI_DIRECT", hop, fromServer = false, source = "mesh from $from")
    }

    /** Verify → de-duplicate → apply → relay. The same path for every bearer. */
    @Synchronized
    fun handleSigned(payloadB64: String, signatureB64: String, bearer: String, hop: Int, fromServer: Boolean, source: String = bearer.lowercase()) {
        val payloadBytes = runCatching { Crypto.unb64(payloadB64) }.getOrNull() ?: return
        val signature = runCatching { Crypto.unb64(signatureB64) }.getOrNull() ?: return
        val p = runCatching { JSONObject(String(payloadBytes)) }.getOrNull() ?: return
        val id = p.optString("id")
        if (id.isBlank() || store.isSeen(id)) return

        val key = store.keys().firstOrNull { it.epochId == p.optInt("epoch") }
        if (key == null) { note("Alarm with unknown key epoch ${p.optInt("epoch")} ($source) — needs sync"); return }
        if (key.status !in USABLE) { note("Alarm signed with ${key.status} key — dropped"); return }
        if (!Crypto.verify(key.publicKey, payloadBytes, signature)) { note("Forged alarm rejected ($source)"); return }
        val createdAt = runCatching { Instant.parse(p.getString("createdAt")).toEpochMilli() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        if (createdAt > now + SKEW_MS || createdAt > key.validTo + GRACE_MS) { note("Stale or future alarm dropped"); return }

        val instanceId = p.getString("instanceId")
        val type = p.getString("type")
        store.markSeen(id, instanceId, type, createdAt, hop, payloadBytes, signature, bearer, uploaded = fromServer)
        val title = p.optString("title", p.optString("code"))

        if (type == "RAISE") {
            if (!store.cancelExists(instanceId)) {
                val area = p.optJSONObject("area")?.let { Area(it.getDouble("lat"), it.getDouble("lon"), it.getInt("radiusM")) }
                store.upsertRaise(LocalAlarm(instanceId, p.getString("definitionId"), p.getString("code"), title,
                    p.getString("scenarioVersionId"), createdAt, null, now, bearer, hop, null, area))
                store.enqueue("RECEPTION", JSONObject().put("alarmInstanceId", instanceId).put("receivedAt", iso(now))
                    .put("viaBearer", bearer).put("hopCount", hop))
                if (source != "this phone") alerts.alarm(instanceId, title, "Open Siren for your orders")
                note("ALARM $title received via $source${if (hop > 0) " ($hop hops)" else ""}")
            }
        } else {
            store.markCancelled(instanceId, createdAt)
            alerts.cancelled(instanceId, title)
            note("All clear: $title cancelled (via $source)")
        }

        if (store.relayMode() == "RELAY" && hop < MAX_HOPS) {
            mesh.broadcast(payloadB64, signatureB64, hop + 1)
            store.markForwarded(id)
        }
        refresh()
    }

    /** Controller phones sign raise / cancel themselves — no server needed. */
    fun raise(control: Control, area: Area?) = signAndSend(control.definitionId, "RAISE", UUID.randomUUID().toString(), area)

    fun cancel(alarm: LocalAlarm) = signAndSend(alarm.definitionId, "CANCEL", alarm.instanceId, null)

    private fun signAndSend(definitionId: String, type: String, instanceId: String, area: Area?): String? {
        val scenario = store.scenarios().firstOrNull { it.definitionId == definitionId } ?: return "Scenario not stored on this phone"
        val orgId = JSONObject(String(scenario.bundle)).getString("organizationId")
        val key = store.keys().filter { it.organizationId == orgId && it.signingKey != null && it.status == "CURRENT" }.maxByOrNull { it.epochId }
            ?: return "This phone holds no signing key for ${scenario.name}"
        val payload = JSONObject()
            .put("v", 1).put("id", UUID.randomUUID().toString()).put("instanceId", instanceId)
            .put("definitionId", definitionId).put("code", scenario.code).put("title", scenario.name)
            .put("scenarioVersionId", scenario.versionId).put("type", type).put("origin", config.deviceId)
            .put("epoch", key.epochId).put("createdAt", Instant.now().toString())
        area?.let { payload.put("area", JSONObject().put("lat", it.lat).put("lon", it.lon).put("radiusM", it.radiusM)) }
        val bytes = payload.toString().toByteArray()
        val signature = Crypto.sign(key.signingKey!!, bytes)
        handleSigned(Crypto.b64(bytes), Crypto.b64(signature), "INTERNET", 0, fromServer = false, source = "this phone")
        return null
    }

    fun acknowledge(alarm: LocalAlarm) {
        val now = System.currentTimeMillis()
        store.acknowledge(alarm.instanceId, now)
        store.enqueue("RECEPTION", JSONObject().put("alarmInstanceId", alarm.instanceId).put("receivedAt", iso(alarm.receivedAt))
            .put("viaBearer", alarm.viaBearer).put("hopCount", alarm.hopCount).put("acknowledgedAt", iso(now)))
        alerts.stopSound()
        refresh()
    }

    fun setTask(alarm: LocalAlarm, taskId: String, state: String) {
        store.setTaskState(alarm.instanceId, taskId, state)
        store.enqueue("TASK", JSONObject().put("alarmInstanceId", alarm.instanceId).put("taskId", taskId)
            .put("state", state).put("reportedAt", iso(System.currentTimeMillis())))
        refresh()
    }

    fun reportPosition(lat: Double, lon: Double, accuracy: Float?) {
        if (_state.value.profile?.mayReportPosition != true) return
        _state.value.activeAlarms.forEach { a ->
            store.enqueue("POSITION", JSONObject().put("alarmInstanceId", a.instanceId).put("lat", lat).put("lon", lon)
                .put("accuracyM", accuracy?.toDouble()).put("reportedAt", iso(System.currentTimeMillis())))
        }
        refresh()
    }

    // ───────────── state ─────────────

    fun refresh() {
        val alarms = store.alarms()
        val keys = store.keys()
        _state.value = _state.value.copy(
            enrolled = config.enrolled,
            profile = parseProfile(),
            lastSyncAt = config.lastSyncAt,
            scenarios = store.scenarios(),
            alarms = alarms,
            taskStates = alarms.associate { it.instanceId to store.taskStates(it.instanceId) },
            outbox = store.outboxSize(),
            relayMode = store.relayMode(),
            keys = keys.size,
            canSign = keys.any { it.signingKey != null },
            log = log.toList(),
        )
    }

    private fun setOnline(online: Boolean, error: String?) {
        if (_state.value.online != online || _state.value.lastError != error) {
            if (!online && _state.value.online) note("Server unreachable — mesh and offline mode active")
            if (online && !_state.value.online && config.lastSyncAt > 0) note("Back online")
            _state.value = _state.value.copy(online = online, lastError = error)
            refresh()
        }
    }

    private fun note(line: String) {
        Log.i(TAG, line)
        synchronized(log) {
            log.addFirst("${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}  $line")
            while (log.size > 40) log.removeLast()
        }
    }

    private fun parseProfile(): Profile? {
        val raw = config.profileJson.ifBlank { return null }
        val j = JSONObject(raw)
        return Profile(
            personName = j.optString("personName"),
            role = j.optString("role").ifBlank { null },
            organizationName = j.optString("organizationName").ifBlank { null },
            domain = j.optString("domain").ifBlank { null },
            deviceClass = j.optString("deviceClass"),
            mayReportPosition = j.optBoolean("mayReportPosition"),
            controls = j.optJSONArray("controls")?.objects()?.map {
                Control(it.getString("alarmDefinitionId"), it.getString("code"), it.getString("name"), it.getBoolean("canRaise"), it.getBoolean("canCancel"))
            }.orEmpty(),
        )
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    companion object {
        private const val TAG = "Siren"
        private const val SYNC_EVERY_MS = 60_000L
        private const val SKEW_MS = 5 * 60_000L
        private const val GRACE_MS = 24 * 3600_000L
        private const val MAX_HOPS = 6
        private val USABLE = setOf("NEXT", "CURRENT", "GRACE")
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
