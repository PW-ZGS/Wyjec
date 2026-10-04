package pl.siren.crypto

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.siren.config.SirenProperties
import java.security.PrivateKey
import java.security.PublicKey
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class KeyEpoch(
    val id: Int,
    val organizationId: UUID,
    val publicKey: ByteArray,
    val privateKeyHsmRef: String,
    val validFrom: Instant,
    val validTo: Instant,
    val status: String,
)

/**
 * Manages alarm key epochs per organization: CURRENT signs today, NEXT is pre-distributed so
 * offline devices survive rotation, GRACE still verifies late copies, RETIRED/COMPROMISED never do.
 */
@Service
class KeyEpochService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val hsm: PrototypeHsm,
    private val props: SirenProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val mapper = RowMapper { rs, _ ->
        KeyEpoch(
            id = rs.getInt("id"),
            organizationId = rs.getObject("organization_id", UUID::class.java),
            publicKey = rs.getBytes("public_key"),
            privateKeyHsmRef = rs.getString("private_key_hsm_ref"),
            validFrom = rs.getTimestamp("valid_from").toInstant(),
            validTo = rs.getTimestamp("valid_to").toInstant(),
            status = rs.getString("status"),
        )
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onStart() = rotate()

    @Scheduled(cron = "0 7 * * * *")
    @Transactional
    fun rotate() {
        val now = Instant.now()
        // CURRENT past its validity → GRACE; GRACE past grace → RETIRED.
        jdbc.update(
            "UPDATE key_epoch SET status = 'GRACE' WHERE status = 'CURRENT' AND valid_to < :now",
            mapOf("now" to Timestamp.from(now)),
        )
        jdbc.update(
            "UPDATE key_epoch SET status = 'RETIRED' WHERE status = 'GRACE' AND valid_to < :cutoff",
            mapOf("cutoff" to Timestamp.from(now.minus(props.alarmGrace))),
        )
        val orgs = jdbc.queryForList("SELECT id FROM organization", emptyMap<String, Any>(), UUID::class.java)
        for (org in orgs) {
            val epochs = forOrganization(org)
            if (epochs.none { it.status == "CURRENT" }) {
                val next = epochs.firstOrNull { it.status == "NEXT" }
                if (next != null) {
                    jdbc.update("UPDATE key_epoch SET status = 'CURRENT' WHERE id = :id", mapOf("id" to next.id))
                } else {
                    create(org, now, "CURRENT")
                }
            }
            if (forOrganization(org).none { it.status == "NEXT" }) {
                val current = forOrganization(org).first { it.status == "CURRENT" }
                create(org, current.validTo, "NEXT")
            }
        }
    }

    private fun create(organizationId: UUID, validFrom: Instant, status: String): Int {
        val pair = AlarmCrypto.generateKeyPair()
        val ref = "alarm-key/$organizationId/${UUID.randomUUID()}"
        hsm.store(ref, pair.private.encoded)
        val id = jdbc.queryForObject(
            """
            INSERT INTO key_epoch (organization_id, public_key, private_key_hsm_ref, valid_from, valid_to, status)
            VALUES (:org, :pub, :ref, :from, :to, :status::key_epoch_status) RETURNING id
            """.trimIndent(),
            mapOf(
                "org" to organizationId,
                "pub" to pair.public.encoded,
                "ref" to ref,
                "from" to Timestamp.from(validFrom),
                "to" to Timestamp.from(validFrom.plus(props.keyEpochValidity)),
                "status" to status,
            ),
            Int::class.java,
        )!!
        log.info("Created {} alarm key epoch {} for organization {}", status, id, organizationId)
        return id
    }

    fun forOrganization(organizationId: UUID): List<KeyEpoch> =
        jdbc.query(
            "SELECT * FROM key_epoch WHERE organization_id = :org ORDER BY valid_from",
            mapOf("org" to organizationId), mapper,
        )

    fun find(id: Int): KeyEpoch? =
        jdbc.query("SELECT * FROM key_epoch WHERE id = :id", mapOf("id" to id), mapper).firstOrNull()

    /** Epochs a device must hold to verify alarms: everything not retired or compromised. */
    fun distributable(organizationIds: Collection<UUID>): List<KeyEpoch> =
        if (organizationIds.isEmpty()) emptyList()
        else jdbc.query(
            "SELECT * FROM key_epoch WHERE organization_id IN (:orgs) AND status IN ('NEXT','CURRENT','GRACE') ORDER BY id",
            mapOf("orgs" to organizationIds), mapper,
        )

    fun current(organizationId: UUID): KeyEpoch =
        forOrganization(organizationId).first { it.status == "CURRENT" }

    fun publicKey(epoch: KeyEpoch): PublicKey = AlarmCrypto.publicKey(epoch.publicKey)

    fun privateKey(epoch: KeyEpoch): PrivateKey =
        AlarmCrypto.privateKey(hsm.load(epoch.privateKeyHsmRef) ?: error("Private key ${epoch.privateKeyHsmRef} missing in HSM"))

    fun privateKeyBytes(epoch: KeyEpoch): ByteArray? = hsm.load(epoch.privateKeyHsmRef)
}
