package pl.siren.alarm

import java.time.Instant
import java.util.UUID

/**
 * Content of a raise / cancel message. Serialized to JSON and signed as raw bytes, so devices
 * verify exactly what was signed — no canonicalization needed. Same shape on Android.
 */
data class AlarmPayload(
    val v: Int = 1,
    val id: UUID,
    val instanceId: UUID,
    val definitionId: UUID,
    val code: String,
    val title: String,
    val scenarioVersionId: UUID,
    val type: String,
    val origin: UUID,
    val epoch: Int,
    val createdAt: Instant,
    val area: AlarmArea? = null,
)

data class AlarmArea(val lat: Double, val lon: Double, val radiusM: Int)

/** Transport form: base64 payload bytes + base64 DER ECDSA signature. Medium-independent. */
data class SignedAlarm(val payload: String, val signature: String)

enum class IngestOutcome { ACCEPTED, DUPLICATE, IGNORED, REJECTED }

data class IngestResult(val outcome: IngestOutcome, val reason: String? = null, val instanceId: UUID? = null)
