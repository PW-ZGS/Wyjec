package pl.siren.alarm

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.siren.scenario.LocationDto
import pl.siren.scenario.ScenarioRepository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class InstanceStats(val participants: Int, val received: Int, val acknowledged: Int, val completed: Int, val tasksDone: Int, val tasksTotal: Int)

data class InstanceSummary(
    val id: UUID,
    val displayNo: Long,
    val definitionId: UUID,
    val code: String,
    val name: String,
    val organizationName: String,
    val domain: String,
    val scenarioVersionId: UUID,
    val version: Int,
    val raisedAt: Instant,
    val raisedBy: String?,
    val cancelledAt: Instant?,
    val cancelledBy: String?,
    val area: AlarmArea?,
    val stats: InstanceStats? = null,
)

data class Reception(val receivedAt: Instant, val acknowledgedAt: Instant?, val viaBearer: String?, val hopCount: Int?)
data class Position(val lat: Double, val lon: Double, val accuracyM: Double?, val reportedAt: Instant)
data class TaskStatus(
    val taskId: UUID,
    val orderNo: Int,
    val description: String,
    val requiresConfirmation: Boolean,
    val state: String,
    val reportedAt: Instant?,
    val note: String?,
)
data class ParticipantGroup(val id: UUID, val name: String, val rank: Int, val location: LocationDto?, val tasks: List<TaskStatus>)

data class Participant(
    val personId: UUID,
    val displayName: String,
    val personRole: String?,
    val shortId: String,
    val role: String,
    val deviceId: UUID?,
    val status: String,
    val blocked: Boolean,
    val reception: Reception?,
    val position: Position?,
    val groups: List<ParticipantGroup>,
)

data class EventRow(
    val id: UUID,
    val alarmInstanceId: UUID,
    val displayNo: Long?,
    val code: String,
    val name: String,
    val eventType: String,
    val originDeviceId: UUID,
    val originName: String?,
    val originPlatform: String,
    val keyEpochId: Int,
    val createdAt: Instant,
    val receivedAt: Instant,
    val firstBearer: String?,
    val verified: Boolean,
    val signatureB64: String,
)

data class InstanceDetail(val summary: InstanceSummary, val participants: List<Participant>, val events: List<EventRow>)

/** Read models for the operations view (map, progress, audit log). */
@Service
class AlarmQueryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val scenarios: ScenarioRepository,
) {
    private val summarySelect = """
        SELECT i.*, a.code, a.name, o.name AS org_name, o.domain, sv.version,
               rp.display_name AS raised_by, cp.display_name AS cancelled_by,
               ST_Y(i.area_center::geometry) AS lat, ST_X(i.area_center::geometry) AS lon
        FROM alarm_instance i
        JOIN alarm_definition a ON a.id = i.alarm_definition_id
        JOIN organization o ON o.id = a.organization_id
        JOIN scenario_version sv ON sv.id = i.scenario_version_id
        LEFT JOIN device rd ON rd.id = i.raised_by_device_id LEFT JOIN person rp ON rp.id = rd.person_id
        LEFT JOIN device cd ON cd.id = i.cancelled_by_device_id LEFT JOIN person cp ON cp.id = cd.person_id
    """.trimIndent()

    private fun mapSummary(rs: ResultSet) = InstanceSummary(
        id = rs.getObject("id", UUID::class.java),
        displayNo = rs.getLong("display_no"),
        definitionId = rs.getObject("alarm_definition_id", UUID::class.java),
        code = rs.getString("code"),
        name = rs.getString("name"),
        organizationName = rs.getString("org_name"),
        domain = rs.getString("domain"),
        scenarioVersionId = rs.getObject("scenario_version_id", UUID::class.java),
        version = rs.getInt("version"),
        raisedAt = rs.getTimestamp("raised_at").toInstant(),
        raisedBy = rs.getString("raised_by"),
        cancelledAt = rs.getTimestamp("cancelled_at")?.toInstant(),
        cancelledBy = rs.getString("cancelled_by"),
        area = rs.getObject("area_radius_m")?.let { AlarmArea(rs.getDouble("lat"), rs.getDouble("lon"), rs.getInt("area_radius_m")) },
    )

    fun active(): List<InstanceSummary> =
        jdbc.query("$summarySelect WHERE i.cancelled_at IS NULL ORDER BY i.raised_at DESC", emptyMap<String, Any>()) { rs, _ -> mapSummary(rs) }
            .map { it.copy(stats = stats(participants(it))) }

    fun history(limit: Int = 50): List<InstanceSummary> =
        jdbc.query("$summarySelect ORDER BY i.raised_at DESC LIMIT :l", mapOf("l" to limit)) { rs, _ -> mapSummary(rs) }
            .map { it.copy(stats = stats(participants(it))) }

    fun summary(id: UUID): InstanceSummary? =
        jdbc.query("$summarySelect WHERE i.id = :id", mapOf("id" to id)) { rs, _ -> mapSummary(rs) }.firstOrNull()

    fun detail(id: UUID): InstanceDetail? {
        val summary = summary(id) ?: return null
        val participants = participants(summary)
        return InstanceDetail(summary.copy(stats = stats(participants)), participants, events(id))
    }

    private fun stats(ps: List<Participant>) = InstanceStats(
        participants = ps.size,
        received = ps.count { it.reception != null },
        acknowledged = ps.count { it.reception?.acknowledgedAt != null },
        completed = ps.count { it.status == "COMPLETED" },
        tasksDone = ps.sumOf { p -> p.groups.sumOf { g -> g.tasks.count { it.state == "DONE" } } },
        tasksTotal = ps.sumOf { p -> p.groups.sumOf { it.tasks.size } },
    )

    fun participants(summary: InstanceSummary): List<Participant> {
        val instanceId = summary.id
        val groups = scenarios.taskGroups(summary.scenarioVersionId)
        val p = mapOf("i" to instanceId)

        val taskStates = jdbc.query(
            """
            SELECT DISTINCT ON (task_id) task_id, state, reported_at, note
            FROM task_progress WHERE alarm_instance_id = :i ORDER BY task_id, reported_at DESC
            """.trimIndent(), p,
        ) { rs, _ -> rs.getObject("task_id", UUID::class.java) to Triple(rs.getString("state"), rs.getTimestamp("reported_at").toInstant(), rs.getString("note")) }
            .toMap()

        val receptions = jdbc.query(
            """
            SELECT DISTINCT ON (d.person_id) d.person_id, r.received_at, r.via_bearer, r.hop_count,
                   (SELECT min(r2.acknowledged_at) FROM alarm_reception r2 JOIN device d2 ON d2.id = r2.device_id
                    WHERE r2.alarm_instance_id = :i AND d2.person_id = d.person_id) AS ack
            FROM alarm_reception r JOIN device d ON d.id = r.device_id
            WHERE r.alarm_instance_id = :i AND d.person_id IS NOT NULL
            ORDER BY d.person_id, r.received_at
            """.trimIndent(), p,
        ) { rs, _ ->
            rs.getObject("person_id", UUID::class.java) to Reception(
                receivedAt = rs.getTimestamp("received_at").toInstant(),
                acknowledgedAt = rs.getTimestamp("ack")?.toInstant(),
                viaBearer = rs.getString("via_bearer"),
                hopCount = rs.getObject("hop_count") as Int?,
            )
        }.toMap()

        val positions = jdbc.query(
            """
            SELECT DISTINCT ON (d.person_id) d.person_id, ST_Y(pr.geom::geometry) AS lat, ST_X(pr.geom::geometry) AS lon,
                   pr.accuracy_m, pr.reported_at
            FROM position_report pr JOIN device d ON d.id = pr.device_id
            WHERE pr.alarm_instance_id = :i AND pr.expires_at > now() AND d.person_id IS NOT NULL
            ORDER BY d.person_id, pr.reported_at DESC
            """.trimIndent(), p,
        ) { rs, _ ->
            rs.getObject("person_id", UUID::class.java) to Position(
                lat = rs.getDouble("lat"), lon = rs.getDouble("lon"),
                accuracyM = rs.getObject("accuracy_m")?.let { (it as Number).toDouble() },
                reportedAt = rs.getTimestamp("reported_at").toInstant(),
            )
        }.toMap()

        val phones = jdbc.query(
            "SELECT DISTINCT ON (person_id) person_id, id FROM device WHERE platform <> 'DESKTOP' AND person_id IS NOT NULL ORDER BY person_id, id",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.toMap()

        data class Entry(val personId: UUID, val name: String, val personRole: String?, val group: ParticipantGroup)
        val entries = groups.flatMap { g ->
            val tasks = g.tasks.map { t ->
                val s = taskStates[t.id]
                TaskStatus(t.id, t.orderNo, t.description, t.requiresConfirmation, s?.first ?: "PENDING", s?.second, s?.third)
            }
            g.assignees.map { a -> Entry(a.personId, a.displayName, a.role, ParticipantGroup(g.id, g.name, a.rank, g.location, tasks)) }
        }
        return entries.groupBy { it.personId }.map { (personId, es) ->
            val reception = receptions[personId]
            val allTasks = es.flatMap { it.group.tasks }
            val status = when {
                allTasks.isNotEmpty() && allTasks.all { it.state == "DONE" } -> "COMPLETED"
                reception?.acknowledgedAt != null -> "ACKNOWLEDGED"
                else -> "UNREAD"
            }
            Participant(
                personId = personId,
                displayName = es.first().name,
                personRole = es.first().personRole,
                shortId = "id#" + personId.toString().takeLast(5).trimStart('0').ifEmpty { "0" },
                role = es.map { it.group.name }.distinct().joinToString(" · "),
                deviceId = phones[personId],
                status = status,
                blocked = allTasks.any { it.state == "BLOCKED" },
                reception = reception,
                position = positions[personId],
                groups = es.map { it.group },
            )
        }
    }

    fun events(instanceId: UUID? = null, limit: Int = 200): List<EventRow> =
        jdbc.query(
            """
            SELECT e.*, i.display_no, a.code, a.name, p.display_name AS origin_name, d.platform,
                   encode(e.signature, 'base64') AS sig
            FROM alarm_event e
            JOIN alarm_definition a ON a.id = e.alarm_definition_id
            JOIN device d ON d.id = e.origin_device_id
            LEFT JOIN person p ON p.id = d.person_id
            LEFT JOIN alarm_instance i ON i.id = e.alarm_instance_id
            WHERE (CAST(:i AS uuid) IS NULL OR e.alarm_instance_id = CAST(:i AS uuid))
            ORDER BY e.received_at DESC LIMIT :l
            """.trimIndent(),
            mapOf("i" to instanceId, "l" to limit),
        ) { rs, _ ->
            EventRow(
                id = rs.getObject("id", UUID::class.java),
                alarmInstanceId = rs.getObject("alarm_instance_id", UUID::class.java),
                displayNo = rs.getObject("display_no") as Long?,
                code = rs.getString("code"),
                name = rs.getString("name"),
                eventType = rs.getString("event_type"),
                originDeviceId = rs.getObject("origin_device_id", UUID::class.java),
                originName = rs.getString("origin_name"),
                originPlatform = rs.getString("platform"),
                keyEpochId = rs.getInt("key_epoch_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                receivedAt = rs.getTimestamp("received_at").toInstant(),
                firstBearer = rs.getString("first_bearer"),
                verified = rs.getBoolean("verified"),
                signatureB64 = rs.getString("sig").replace("\n", ""),
            )
        }
}
