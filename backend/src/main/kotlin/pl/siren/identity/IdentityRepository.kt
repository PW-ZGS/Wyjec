package pl.siren.identity

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import pl.siren.crypto.PrototypeHsm
import java.time.Instant
import java.util.UUID

data class DeviceInfo(
    val id: UUID,
    val personId: UUID?,
    val personName: String?,
    val personRole: String?,
    val organizationId: UUID?,
    val organizationName: String?,
    val domain: String?,
    val deviceClassId: Int,
    val deviceClassCode: String,
    val criticality: String,
    val defaultRelayMode: String,
    val relayModeOverride: String?,
    val mayReportPosition: Boolean,
    val platform: String,
    val status: String,
    val lastSeenAt: Instant?,
)

data class Bearer(val bearer: String, val priority: Int, val address: String?)

data class PersonRow(
    val id: UUID,
    val displayName: String,
    val role: String?,
    val phone: String?,
    val organizationId: UUID,
    val organizationName: String,
    val domain: String,
    val isActive: Boolean,
)

@Repository
class IdentityRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val hsm: PrototypeHsm,
) {
    private val deviceMapper = RowMapper { rs, _ ->
        DeviceInfo(
            id = rs.getObject("id", UUID::class.java),
            personId = rs.getObject("person_id", UUID::class.java),
            personName = rs.getString("display_name"),
            personRole = rs.getString("role"),
            organizationId = rs.getObject("organization_id", UUID::class.java),
            organizationName = rs.getString("org_name"),
            domain = rs.getString("domain"),
            deviceClassId = rs.getInt("device_class_id"),
            deviceClassCode = rs.getString("class_code"),
            criticality = rs.getString("criticality"),
            defaultRelayMode = rs.getString("default_relay_mode"),
            relayModeOverride = rs.getString("relay_mode_override"),
            mayReportPosition = rs.getBoolean("may_report_position"),
            platform = rs.getString("platform"),
            status = rs.getString("status"),
            lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant(),
        )
    }

    private val deviceSelect = """
        SELECT d.id, d.person_id, d.device_class_id, d.platform, d.relay_mode_override, d.status, d.last_seen_at,
               p.display_name, p.role, p.organization_id, o.name AS org_name, o.domain,
               c.code AS class_code, c.criticality, c.default_relay_mode, c.may_report_position
        FROM device d
        JOIN device_class c ON c.id = d.device_class_id
        LEFT JOIN person p ON p.id = d.person_id
        LEFT JOIN organization o ON o.id = p.organization_id
    """.trimIndent()

    fun findDevice(id: UUID): DeviceInfo? =
        jdbc.query("$deviceSelect WHERE d.id = :id", mapOf("id" to id), deviceMapper).firstOrNull()

    fun devices(): List<DeviceInfo> =
        jdbc.query("$deviceSelect ORDER BY o.name, p.display_name, d.platform", emptyMap<String, Any>(), deviceMapper)

    fun devicesOfPerson(personId: UUID): List<DeviceInfo> =
        jdbc.query("$deviceSelect WHERE d.person_id = :p ORDER BY d.platform", mapOf("p" to personId), deviceMapper)

    /** PSK of the device's currently valid, non-revoked credential. */
    fun activePsk(deviceId: UUID): ByteArray? {
        val ref = jdbc.query(
            """
            SELECT psk_hsm_ref FROM device_credential
            WHERE device_id = :d AND revoked_at IS NULL AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
            ORDER BY valid_from DESC LIMIT 1
            """.trimIndent(),
            mapOf("d" to deviceId),
        ) { rs, _ -> rs.getString(1) }.firstOrNull() ?: return null
        return hsm.load(ref)
    }

    fun touch(deviceId: UUID) {
        jdbc.update("UPDATE device SET last_seen_at = now() WHERE id = :d", mapOf("d" to deviceId))
    }

    fun bearers(deviceId: UUID): List<Bearer> =
        jdbc.query(
            "SELECT bearer, priority, address FROM device_bearer WHERE device_id = :d ORDER BY priority",
            mapOf("d" to deviceId),
        ) { rs, _ -> Bearer(rs.getString("bearer"), rs.getInt("priority"), rs.getString("address")) }

    fun people(): List<PersonRow> =
        jdbc.query(
            """
            SELECT p.*, o.name AS org_name, o.domain FROM person p JOIN organization o ON o.id = p.organization_id
            ORDER BY o.domain, o.name, p.display_name
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            PersonRow(
                id = rs.getObject("id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = rs.getString("role"),
                phone = rs.getString("phone"),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                organizationName = rs.getString("org_name"),
                domain = rs.getString("domain"),
                isActive = rs.getBoolean("is_active"),
            )
        }

    fun organizations(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT id, name, domain, parent_id FROM organization ORDER BY domain, name", emptyMap<String, Any>())
}
