package pl.siren.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.siren.crypto.Hashing
import java.util.UUID

data class DeviceBundle(val bundle: ScenarioBundle, val bytes: ByteArray, val sha256: ByteArray)

/**
 * Builds scenario bundles. The full bundle is written to the object store on publish; devices get a
 * filtered copy with only their own task groups, so a lost phone reveals minimal plan detail.
 */
@Service
class BundleService(
    private val scenarios: ScenarioRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Order(0)
    fun publishPending() {
        for (version in scenarios.publishedWithoutBundle()) {
            val bundle = scenarios.bundle(version.id) ?: continue
            val bytes = objectMapper.writeValueAsBytes(bundle)
            val key = "scenarios/${bundle.alarmCode.lowercase()}/v${bundle.version}.json"
            jdbc.update(
                """
                INSERT INTO prototype_object_store (object_key, content, content_type) VALUES (:k, :c, 'application/json')
                ON CONFLICT (object_key) DO UPDATE SET content = :c
                """.trimIndent(),
                mapOf("k" to key, "c" to bytes),
            )
            scenarios.setBundle(version.id, key, Hashing.sha256(bytes))
            log.info("Published scenario bundle {}", key)
        }
    }

    fun forPerson(versionId: UUID, personId: UUID): DeviceBundle? {
        val full = scenarios.bundle(versionId) ?: return null
        val own = full.copy(taskGroups = full.taskGroups.filter { g -> g.assignees.any { it.personId == personId } })
        val bytes = objectMapper.writeValueAsBytes(own)
        return DeviceBundle(own, bytes, Hashing.sha256(bytes))
    }
}
