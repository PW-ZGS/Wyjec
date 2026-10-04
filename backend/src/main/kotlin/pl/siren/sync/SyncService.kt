package pl.siren.sync

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.siren.crypto.Hashing
import pl.siren.crypto.KeyEpochService
import pl.siren.identity.DeviceInfo
import pl.siren.identity.IdentityRepository
import pl.siren.scenario.BundleService
import pl.siren.scenario.ControllerRight
import pl.siren.scenario.ScenarioRepository
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class SyncDevice(val id: UUID, val platform: String, val deviceClass: String, val criticality: String, val mayReportPosition: Boolean)
data class SyncPerson(val id: UUID, val displayName: String, val role: String?, val organizationName: String?, val domain: String?)
data class SyncPolicy(val relayMode: String, val allowedBearers: List<String>, val signedByServer: String)
data class SyncKey(
    val keyEpochId: Int,
    val organizationId: UUID,
    val publicKey: String,
    val privateKey: String?,
    val validFrom: Instant,
    val validTo: Instant,
    val status: String,
)
data class SyncScenario(
    val scenarioVersionId: UUID,
    val alarmDefinitionId: UUID,
    val alarmCode: String,
    val alarmName: String,
    val version: Int,
    val bundleSha256: String,
    val bundle: String,
)
data class SyncResponse(
    val serverTime: Instant,
    val device: SyncDevice,
    val person: SyncPerson?,
    val policy: SyncPolicy,
    val keys: List<SyncKey>,
    val scenarios: List<SyncScenario>,
    val controls: List<ControllerRight>,
)

/**
 * Scenario + key distribution over the Server–App channel. Devices store the result offline and
 * keep working from it when no server is reachable.
 */
@Service
class SyncService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val identity: IdentityRepository,
    private val scenarios: ScenarioRepository,
    private val bundles: BundleService,
    private val keys: KeyEpochService,
    private val objectMapper: ObjectMapper,
) {
    private val b64 = Base64.getEncoder()

    /** Alarms are system-wide: every personal device receives every alarm. */
    fun relevantDefinitions(device: DeviceInfo): Set<UUID> =
        if (device.personId == null) emptySet() else scenarios.definitions().map { it.id }.toSet()

    @Transactional
    fun sync(device: DeviceInfo, psk: ByteArray): SyncResponse {
        val personId = device.personId
        val controls = personId?.let { scenarios.controllerRights(it) }.orEmpty()
        val definitions = relevantDefinitions(device).mapNotNull { scenarios.definition(it) }

        val scenarioList = definitions.mapNotNull { def ->
            val versionId = def.currentScenarioVersionId ?: return@mapNotNull null
            val bundle = personId?.let { bundles.forPerson(versionId, it) } ?: return@mapNotNull null
            SyncScenario(
                scenarioVersionId = versionId,
                alarmDefinitionId = def.id,
                alarmCode = def.code,
                alarmName = def.name,
                version = def.currentVersion ?: 0,
                bundleSha256 = Hashing.hex(bundle.sha256),
                bundle = b64.encodeToString(bundle.bytes),
            )
        }

        val organizations = (definitions.map { it.organizationId } + listOfNotNull(device.organizationId)).toSet()
        val controllerOrgs = controls.filter { it.canRaise || it.canCancel }
            .mapNotNull { c -> definitions.firstOrNull { it.id == c.alarmDefinitionId }?.organizationId }.toSet()
        val keyList = keys.distributable(organizations).map { e ->
            SyncKey(
                keyEpochId = e.id,
                organizationId = e.organizationId,
                publicKey = b64.encodeToString(e.publicKey),
                // Only alarm controllers receive the signing key.
                privateKey = if (e.organizationId in controllerOrgs && e.status != "GRACE") keys.privateKeyBytes(e)?.let(b64::encodeToString) else null,
                validFrom = e.validFrom,
                validTo = e.validTo,
                status = e.status,
            )
        }

        val relayMode = effectiveRelayMode(device, scenarioList.map { it.scenarioVersionId })
        val bearers = identity.bearers(device.id).map { it.bearer }
        val policyBytes = objectMapper.writeValueAsBytes(mapOf("deviceId" to device.id, "relayMode" to relayMode, "allowedBearers" to bearers))
        val policy = SyncPolicy(relayMode, bearers, b64.encodeToString(Hashing.hmacSha256(psk, policyBytes)))

        jdbc.update(
            """
            INSERT INTO device_sync_state (device_id, scenario_version_ids, key_epoch_ids, last_sync_at)
            VALUES (:d, :s, :k, now())
            ON CONFLICT (device_id) DO UPDATE SET scenario_version_ids = :s, key_epoch_ids = :k, last_sync_at = now()
            """.trimIndent(),
            mapOf(
                "d" to device.id,
                "s" to scenarioList.map { it.scenarioVersionId }.toTypedArray(),
                "k" to keyList.map { it.keyEpochId }.toTypedArray(),
            ),
        )

        return SyncResponse(
            serverTime = Instant.now(),
            device = SyncDevice(device.id, device.platform, device.deviceClassCode, device.criticality, device.mayReportPosition),
            person = personId?.let { SyncPerson(it, device.personName!!, device.personRole, device.organizationName, device.domain) },
            policy = policy,
            keys = keyList,
            scenarios = scenarioList,
            controls = controls,
        )
    }

    /** relay_mode_override ?? relay_policy_override (active scenario) ?? device_class.default_relay_mode. */
    private fun effectiveRelayMode(device: DeviceInfo, versionIds: List<UUID>): String {
        device.relayModeOverride?.let { return it }
        if (versionIds.isNotEmpty()) {
            val active = jdbc.queryForList(
                "SELECT scenario_version_id FROM alarm_instance WHERE cancelled_at IS NULL AND scenario_version_id IN (:v)",
                mapOf("v" to versionIds), UUID::class.java,
            )
            active.firstNotNullOfOrNull { scenarios.relayOverride(it, device.deviceClassId) }?.let { return it }
        }
        return device.defaultRelayMode
    }
}
