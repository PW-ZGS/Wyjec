package pl.siren.status

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.siren.config.SirenProperties
import pl.siren.identity.DeviceInfo
import pl.siren.live.LiveUpdates
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class ReceptionReport(
    val alarmInstanceId: UUID,
    val receivedAt: Instant,
    val viaBearer: String? = null,
    val hopCount: Int? = null,
    val acknowledgedAt: Instant? = null,
    val helpRequestedAt: Instant? = null,
)

data class TaskReport(
    val alarmInstanceId: UUID,
    val taskId: UUID,
    val state: String,
    val reportedAt: Instant,
    val note: String? = null,
)

data class PositionReport(
    val alarmInstanceId: UUID,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    val reportedAt: Instant,
)

data class StatusBatch(
    val receptions: List<ReceptionReport> = emptyList(),
    val tasks: List<TaskReport> = emptyList(),
    val positions: List<PositionReport> = emptyList(),
)

data class StatusResult(val accepted: Int, val rejected: List<String>)

/** Operational status sent over the Server–App channel whenever an online path exists. */
@Service
class StatusService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: SirenProperties,
    private val live: LiveUpdates,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val taskStates = setOf("PENDING", "EN_ROUTE", "IN_PROGRESS", "DONE", "BLOCKED")

    @Transactional
    fun record(device: DeviceInfo, batch: StatusBatch): StatusResult {
        var accepted = 0
        val rejected = mutableListOf<String>()
        for (r in batch.receptions) {
            if (!instanceExists(r.alarmInstanceId)) { rejected += "reception: unknown alarm ${r.alarmInstanceId}"; continue }
            jdbc.update(
                """
                INSERT INTO alarm_reception (alarm_instance_id, device_id, received_at, via_bearer, hop_count, acknowledged_at, help_requested_at)
                VALUES (:i, :d, :r, :b::bearer, :h, :a, :help)
                ON CONFLICT (alarm_instance_id, device_id) DO UPDATE SET
                    received_at     = LEAST(alarm_reception.received_at, EXCLUDED.received_at),
                    via_bearer      = CASE WHEN EXCLUDED.received_at < alarm_reception.received_at
                                           THEN EXCLUDED.via_bearer ELSE alarm_reception.via_bearer END,
                    hop_count       = CASE WHEN EXCLUDED.received_at < alarm_reception.received_at
                                           THEN EXCLUDED.hop_count ELSE alarm_reception.hop_count END,
                    acknowledged_at = COALESCE(alarm_reception.acknowledged_at, EXCLUDED.acknowledged_at),
                    help_requested_at = COALESCE(EXCLUDED.help_requested_at, alarm_reception.help_requested_at)
                """.trimIndent(),
                mapOf(
                    "i" to r.alarmInstanceId, "d" to device.id, "r" to Timestamp.from(r.receivedAt),
                    "b" to r.viaBearer, "h" to r.hopCount, "a" to r.acknowledgedAt?.let(Timestamp::from),
                    "help" to r.helpRequestedAt?.let(Timestamp::from),
                ),
            )
            accepted++
        }
        for (t in batch.tasks) {
            val personId = device.personId
            if (personId == null) { rejected += "task: device has no person"; continue }
            if (t.state !in taskStates) { rejected += "task: bad state ${t.state}"; continue }
            if (!taskBelongsToInstance(t.taskId, t.alarmInstanceId)) { rejected += "task: ${t.taskId} not in alarm"; continue }
            jdbc.update(
                """
                INSERT INTO task_progress (alarm_instance_id, task_id, person_id, state, reported_at, note)
                VALUES (:i, :t, :p, :s::task_state, :r, :n)
                """.trimIndent(),
                mapOf(
                    "i" to t.alarmInstanceId, "t" to t.taskId, "p" to personId, "s" to t.state,
                    "r" to Timestamp.from(t.reportedAt), "n" to t.note,
                ),
            )
            accepted++
        }
        for (pos in batch.positions) {
            if (!device.mayReportPosition) { rejected += "position: not allowed for ${device.deviceClassCode}"; continue }
            if (!instanceActive(pos.alarmInstanceId)) { rejected += "position: alarm not active"; continue }
            jdbc.update(
                """
                INSERT INTO position_report (device_id, reported_at, alarm_instance_id, geom, accuracy_m, expires_at)
                VALUES (:d, :r, :i, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :acc, :exp)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                mapOf(
                    "d" to device.id, "r" to Timestamp.from(pos.reportedAt), "i" to pos.alarmInstanceId,
                    "lat" to pos.lat, "lon" to pos.lon, "acc" to pos.accuracyM,
                    "exp" to Timestamp.from(pos.reportedAt.plus(props.positionRetention)),
                ),
            )
            accepted++
        }
        if (accepted > 0) live.publish("status")
        if (rejected.isNotEmpty()) log.debug("Device {} status rejected: {}", device.id, rejected)
        return StatusResult(accepted, rejected)
    }

    private fun instanceExists(id: UUID) =
        jdbc.queryForObject("SELECT count(*) FROM alarm_instance WHERE id = :id", mapOf("id" to id), Int::class.java)!! > 0

    private fun instanceActive(id: UUID) =
        jdbc.queryForObject(
            "SELECT count(*) FROM alarm_instance WHERE id = :id AND cancelled_at IS NULL",
            mapOf("id" to id), Int::class.java,
        )!! > 0

    private fun taskBelongsToInstance(taskId: UUID, instanceId: UUID) =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM task t JOIN task_group g ON g.id = t.task_group_id
            JOIN alarm_instance i ON i.scenario_version_id = g.scenario_version_id
            WHERE t.id = :t AND i.id = :i
            """.trimIndent(),
            mapOf("t" to taskId, "i" to instanceId), Int::class.java,
        )!! > 0

    /** Data minimization: positions are deleted once expired. */
    @Scheduled(fixedRate = 600_000)
    fun purgeExpiredPositions() {
        val n = jdbc.update("DELETE FROM position_report WHERE expires_at < now()", emptyMap<String, Any>())
        if (n > 0) log.info("Purged {} expired position reports", n)
    }
}
