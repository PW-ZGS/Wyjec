package pl.siren.alarm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.siren.config.SirenProperties
import pl.siren.crypto.AlarmCrypto
import pl.siren.crypto.KeyEpochService
import pl.siren.identity.IdentityRepository
import pl.siren.live.LiveUpdates
import pl.siren.scenario.ScenarioRepository
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID

class AlarmCommandException(message: String) : RuntimeException(message)

/**
 * Accepts signed raise / cancel messages from any path (console, controller phone, relayed copy),
 * verifies them against the key epoch and applies the replay rules. Authentic but unacceptable
 * messages (stale, unauthorized origin) are logged with verified = false for audit.
 *
 *  - alarm_event.id is unique: repeated copies are duplicates;
 *  - CANCEL is terminal for an alarm instance;
 *  - events older than key_epoch.valid_to + grace are stale.
 */
@Service
class AlarmService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val keys: KeyEpochService,
    private val scenarios: ScenarioRepository,
    private val identity: IdentityRepository,
    private val objectMapper: ObjectMapper,
    private val props: SirenProperties,
    private val live: LiveUpdates,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val usableEpochStates = setOf("NEXT", "CURRENT", "GRACE")

    @Transactional
    fun ingest(signed: SignedAlarm, bearer: String?): IngestResult {
        val payloadBytes = decode(signed.payload) ?: return rejected("payload is not base64")
        val signature = decode(signed.signature) ?: return rejected("signature is not base64")
        val p = runCatching { objectMapper.readValue<AlarmPayload>(payloadBytes) }.getOrNull()
            ?: return rejected("malformed payload")
        if (p.type != "RAISE" && p.type != "CANCEL") return rejected("unknown event type ${p.type}")

        val epoch = keys.find(p.epoch) ?: return rejected("unknown key epoch ${p.epoch}")
        val definition = scenarios.definition(p.definitionId) ?: return rejected("unknown alarm definition")
        if (definition.organizationId != epoch.organizationId) return rejected("key epoch belongs to another organization")
        val version = scenarios.version(p.scenarioVersionId)
        if (version == null || version.alarmDefinitionId != definition.id) return rejected("scenario version does not match alarm")
        val origin = identity.findDevice(p.origin) ?: return rejected("unknown origin device")

        // Forged or tampered copies are never persisted: storing them under the message id would
        // let an attacker shadow the genuine message that arrives later over another path.
        if (epoch.status !in usableEpochStates) return rejected("key epoch ${epoch.id} is ${epoch.status}")
        if (!AlarmCrypto.verify(keys.publicKey(epoch), payloadBytes, signature)) return rejected("invalid signature")

        if (eventExists(p.id)) return IngestResult(IngestOutcome.DUPLICATE, instanceId = p.instanceId)

        val now = Instant.now()
        val reason = when {
            p.createdAt.isAfter(now.plus(props.maxClockSkew)) -> "signed timestamp is in the future"
            p.createdAt.isBefore(epoch.validFrom.minus(props.maxClockSkew)) ||
                p.createdAt.isAfter(epoch.validTo.plus(props.alarmGrace)) -> "stale: outside key epoch validity"
            !isAuthorized(origin.personId, definition.id, p.type) -> "origin device is not an authorized controller"
            else -> null
        }

        jdbc.update(
            """
            INSERT INTO alarm_event (id, alarm_instance_id, alarm_definition_id, scenario_version_id, event_type,
                                     origin_device_id, key_epoch_id, created_at, received_at, first_bearer,
                                     signed_payload, signature, verified)
            VALUES (:id, :instance, :definition, :version, :type::alarm_event_type, :origin, :epoch, :created, now(),
                    :bearer::bearer, :payload, :signature, :verified)
            """.trimIndent(),
            mapOf(
                "id" to p.id, "instance" to p.instanceId, "definition" to definition.id, "version" to version.id,
                "type" to p.type, "origin" to origin.id, "epoch" to epoch.id, "created" to Timestamp.from(p.createdAt),
                "bearer" to bearer, "payload" to payloadBytes, "signature" to signature, "verified" to (reason == null),
            ),
        )

        if (reason != null) {
            log.warn("Rejected alarm event {} ({}): {}", p.id, p.type, reason)
            live.publish("events")
            return IngestResult(IngestOutcome.REJECTED, reason, p.instanceId)
        }

        val result = applyToInstance(p)
        log.info("Alarm event {} {} {} → {}", p.id, p.type, definition.code, result.outcome)
        live.publish("alarms")
        return result
    }

    private fun applyToInstance(p: AlarmPayload): IngestResult {
        val existing = jdbc.query(
            "SELECT cancelled_at FROM alarm_instance WHERE id = :id",
            mapOf("id" to p.instanceId),
        ) { rs, _ -> Optional(rs.getTimestamp(1)) }.firstOrNull()

        if (existing?.value != null) {
            return IngestResult(IngestOutcome.IGNORED, "alarm instance already cancelled", p.instanceId)
        }
        val created = Timestamp.from(p.createdAt)
        if (existing == null) {
            jdbc.update(
                """
                INSERT INTO alarm_instance (id, alarm_definition_id, scenario_version_id, raised_at, raised_by_device_id,
                                            cancelled_at, cancelled_by_device_id, area_center, area_radius_m)
                VALUES (:id, :definition, :version, :created, :origin, :cancelled, :cancelledBy,
                        CASE WHEN :lat::float8 IS NULL THEN NULL
                             ELSE ST_SetSRID(ST_MakePoint(:lon::float8, :lat::float8), 4326)::geography END,
                        :radius)
                """.trimIndent(),
                mapOf(
                    "id" to p.instanceId, "definition" to p.definitionId, "version" to p.scenarioVersionId,
                    "created" to created, "origin" to p.origin,
                    "cancelled" to if (p.type == "CANCEL") created else null,
                    "cancelledBy" to if (p.type == "CANCEL") p.origin else null,
                    "lat" to p.area?.lat, "lon" to p.area?.lon, "radius" to p.area?.radiusM,
                ),
            )
            return IngestResult(IngestOutcome.ACCEPTED, instanceId = p.instanceId)
        }
        if (p.type == "RAISE") return IngestResult(IngestOutcome.IGNORED, "alarm instance already raised", p.instanceId)
        jdbc.update(
            "UPDATE alarm_instance SET cancelled_at = :c, cancelled_by_device_id = :o WHERE id = :id AND cancelled_at IS NULL",
            mapOf("c" to created, "o" to p.origin, "id" to p.instanceId),
        )
        return IngestResult(IngestOutcome.ACCEPTED, instanceId = p.instanceId)
    }

    private data class Optional<T>(val value: T?)

    private fun rejected(reason: String): IngestResult {
        log.warn("Rejected alarm message: {}", reason)
        return IngestResult(IngestOutcome.REJECTED, reason)
    }

    private fun eventExists(id: UUID): Boolean =
        jdbc.queryForObject("SELECT count(*) FROM alarm_event WHERE id = :id", mapOf("id" to id), Int::class.java)!! > 0

    fun isAuthorized(personId: UUID?, definitionId: UUID, type: String): Boolean {
        if (personId == null) return false
        val column = if (type == "RAISE") "can_raise" else "can_cancel"
        return jdbc.queryForObject(
            "SELECT count(*) FROM alarm_controller WHERE person_id = :p AND alarm_definition_id = :a AND $column",
            mapOf("p" to personId, "a" to definitionId), Int::class.java,
        )!! > 0
    }

    // ───────────── Console (admin panel) commands ─────────────

    @Transactional
    /** Raise on behalf of an operator: the server signs with the HSM key, acting as the operator's console device. */
    fun raiseFromConsole(personId: UUID, consoleDeviceId: UUID, definitionId: UUID, area: AlarmArea?): IngestResult {
        val definition = scenarios.definition(definitionId) ?: throw AlarmCommandException("Unknown alarm")
        if (!isAuthorized(personId, definitionId, "RAISE")) throw AlarmCommandException("You are not allowed to raise ${definition.name}")
        val version = definition.currentScenarioVersionId ?: throw AlarmCommandException("Alarm has no published scenario")
        val payload = AlarmPayload(
            id = UUID.randomUUID(), instanceId = UUID.randomUUID(), definitionId = definitionId, code = definition.code,
            title = definition.name, scenarioVersionId = version, type = "RAISE", origin = consoleDeviceId,
            epoch = keys.current(definition.organizationId).id, createdAt = Instant.now(), area = area,
        )
        return ingest(sign(payload, definition.organizationId), "INTERNET")
    }

    @Transactional
    fun cancelFromConsole(personId: UUID, consoleDeviceId: UUID, instanceId: UUID): IngestResult {
        val row = jdbc.query(
            "SELECT alarm_definition_id, scenario_version_id FROM alarm_instance WHERE id = :id",
            mapOf("id" to instanceId),
        ) { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.firstOrNull()
            ?: throw AlarmCommandException("Unknown alarm instance")
        val definition = scenarios.definition(row.first)!!
        if (!isAuthorized(personId, definition.id, "CANCEL")) throw AlarmCommandException("You are not allowed to cancel ${definition.name}")
        val payload = AlarmPayload(
            id = UUID.randomUUID(), instanceId = instanceId, definitionId = definition.id, code = definition.code,
            title = definition.name, scenarioVersionId = row.second, type = "CANCEL", origin = consoleDeviceId,
            epoch = keys.current(definition.organizationId).id, createdAt = Instant.now(),
        )
        return ingest(sign(payload, definition.organizationId), "INTERNET")
    }

    private fun sign(payload: AlarmPayload, organizationId: UUID): SignedAlarm {
        val epoch = keys.current(organizationId)
        val bytes = objectMapper.writeValueAsBytes(payload)
        val signature = AlarmCrypto.sign(keys.privateKey(epoch), bytes)
        val b64 = Base64.getEncoder()
        return SignedAlarm(b64.encodeToString(bytes), b64.encodeToString(signature))
    }

    /** Verified messages a device should hold, for the given alarm definitions. */
    fun verifiedSince(since: Instant, definitionIds: Collection<UUID>): List<SignedAlarm> {
        if (definitionIds.isEmpty()) return emptyList()
        val b64 = Base64.getEncoder()
        return jdbc.query(
            """
            SELECT signed_payload, signature FROM alarm_event
            WHERE verified AND received_at > :since AND alarm_definition_id IN (:defs)
            ORDER BY created_at
            """.trimIndent(),
            mapOf("since" to Timestamp.from(since), "defs" to definitionIds),
        ) { rs, _ -> SignedAlarm(b64.encodeToString(rs.getBytes(1)), b64.encodeToString(rs.getBytes(2))) }
    }

    private fun decode(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
}
