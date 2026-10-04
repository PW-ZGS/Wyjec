package pl.siren.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.siren.alarm.AlarmService
import pl.siren.alarm.IngestResult
import pl.siren.alarm.SignedAlarm
import pl.siren.identity.DeviceInfo
import pl.siren.status.StatusBatch
import pl.siren.status.StatusResult
import pl.siren.status.StatusService
import pl.siren.sync.SyncResponse
import pl.siren.sync.SyncService
import java.time.Instant
import java.time.temporal.ChronoUnit

data class AlarmSubmission(val payload: String, val signature: String, val bearer: String? = null, val hopCount: Int? = null)
data class AlarmFeed(val serverTime: Instant, val alarms: List<SignedAlarm>)

/** Server–App channel. All requests are HMAC-authenticated by [DeviceAuthFilter]. */
@RestController
@RequestMapping("/api/device")
class DeviceController(
    private val sync: SyncService,
    private val alarms: AlarmService,
    private val status: StatusService,
) {
    @GetMapping("/sync")
    fun sync(
        @RequestAttribute(DeviceAuthFilter.DEVICE_ATTR) device: DeviceInfo,
        @RequestAttribute(DeviceAuthFilter.PSK_ATTR) psk: ByteArray,
    ): SyncResponse = sync.sync(device, psk)

    /** Signed alarm messages since a point in time. Devices verify each one themselves. */
    @GetMapping("/alarms")
    fun alarms(
        @RequestAttribute(DeviceAuthFilter.DEVICE_ATTR) device: DeviceInfo,
        @RequestParam(required = false) since: Long?,
    ): AlarmFeed {
        val floor = Instant.now().minus(48, ChronoUnit.HOURS)
        val from = since?.let { Instant.ofEpochMilli(it) }?.takeIf { it.isAfter(floor) } ?: floor
        return AlarmFeed(Instant.now(), alarms.verifiedSince(from, sync.relevantDefinitions(device)))
    }

    /** A controller phone raising/cancelling, or any device bridging a copy it got over the mesh. */
    @PostMapping("/alarms")
    fun submit(@RequestBody body: AlarmSubmission): IngestResult =
        alarms.ingest(SignedAlarm(body.payload, body.signature), body.bearer ?: "INTERNET")

    @PostMapping("/status")
    fun status(
        @RequestAttribute(DeviceAuthFilter.DEVICE_ATTR) device: DeviceInfo,
        @RequestBody batch: StatusBatch,
    ): StatusResult = status.record(device, batch)
}
