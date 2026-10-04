package pl.siren.scenario

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class AlarmDefinitionRow(
    val id: UUID,
    val organizationId: UUID,
    val organizationName: String,
    val domain: String,
    val code: String,
    val name: String,
    val currentScenarioVersionId: UUID?,
    val currentVersion: Int?,
)

data class ScenarioVersionRow(
    val id: UUID,
    val alarmDefinitionId: UUID,
    val version: Int,
    val status: String,
    val bundleObjectKey: String?,
    val bundleSha256: ByteArray?,
    val publishedAt: Instant?,
)

data class LocationDto(val id: UUID, val name: String, val address: String?, val lat: Double, val lon: Double)
data class TaskDto(val id: UUID, val orderNo: Int, val description: String, val requiresConfirmation: Boolean)
data class AssigneeDto(val personId: UUID, val displayName: String, val role: String?, val rank: Int)
data class TaskGroupDto(
    val id: UUID,
    val name: String,
    val orderNo: Int,
    val location: LocationDto?,
    val assignees: List<AssigneeDto>,
    val tasks: List<TaskDto>,
)

/** Content of a published scenario version — what devices store offline. */
data class ScenarioBundle(
    val scenarioVersionId: UUID,
    val alarmDefinitionId: UUID,
    val alarmCode: String,
    val alarmName: String,
    val organizationId: UUID,
    val version: Int,
    val taskGroups: List<TaskGroupDto>,
)

data class ControllerRight(val alarmDefinitionId: UUID, val code: String, val name: String, val canRaise: Boolean, val canCancel: Boolean)

@Repository
class ScenarioRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val definitionSelect = """
        SELECT a.id, a.organization_id, o.name AS org_name, o.domain, a.code, a.name,
               a.current_scenario_version_id, sv.version
        FROM alarm_definition a
        JOIN organization o ON o.id = a.organization_id
        LEFT JOIN scenario_version sv ON sv.id = a.current_scenario_version_id
    """.trimIndent()

    private fun mapDefinition(rs: ResultSet) = AlarmDefinitionRow(
        id = rs.getObject("id", UUID::class.java),
        organizationId = rs.getObject("organization_id", UUID::class.java),
        organizationName = rs.getString("org_name"),
        domain = rs.getString("domain"),
        code = rs.getString("code"),
        name = rs.getString("name"),
        currentScenarioVersionId = rs.getObject("current_scenario_version_id", UUID::class.java),
        currentVersion = rs.getObject("version") as Int?,
    )

    fun definitions(): List<AlarmDefinitionRow> =
        jdbc.query("$definitionSelect ORDER BY o.domain, o.name, a.name", emptyMap<String, Any>()) { rs, _ -> mapDefinition(rs) }

    fun definition(id: UUID): AlarmDefinitionRow? =
        jdbc.query("$definitionSelect WHERE a.id = :id", mapOf("id" to id)) { rs, _ -> mapDefinition(rs) }.firstOrNull()

    fun versions(alarmDefinitionId: UUID): List<ScenarioVersionRow> =
        jdbc.query(
            "SELECT * FROM scenario_version WHERE alarm_definition_id = :a ORDER BY version DESC",
            mapOf("a" to alarmDefinitionId),
        ) { rs, _ -> mapVersion(rs) }

    fun version(id: UUID): ScenarioVersionRow? =
        jdbc.query("SELECT * FROM scenario_version WHERE id = :id", mapOf("id" to id)) { rs, _ -> mapVersion(rs) }.firstOrNull()

    fun publishedWithoutBundle(): List<ScenarioVersionRow> =
        jdbc.query(
            "SELECT * FROM scenario_version WHERE status = 'PUBLISHED' AND bundle_sha256 IS NULL",
            emptyMap<String, Any>(),
        ) { rs, _ -> mapVersion(rs) }

    private fun mapVersion(rs: ResultSet) = ScenarioVersionRow(
        id = rs.getObject("id", UUID::class.java),
        alarmDefinitionId = rs.getObject("alarm_definition_id", UUID::class.java),
        version = rs.getInt("version"),
        status = rs.getString("status"),
        bundleObjectKey = rs.getString("bundle_object_key"),
        bundleSha256 = rs.getBytes("bundle_sha256"),
        publishedAt = rs.getTimestamp("published_at")?.toInstant(),
    )

    fun setBundle(versionId: UUID, objectKey: String, sha256: ByteArray) {
        jdbc.update(
            "UPDATE scenario_version SET bundle_object_key = :k, bundle_sha256 = :s WHERE id = :id",
            mapOf("k" to objectKey, "s" to sha256, "id" to versionId),
        )
    }

    fun bundle(versionId: UUID): ScenarioBundle? {
        val version = version(versionId) ?: return null
        val definition = definition(version.alarmDefinitionId) ?: return null
        return ScenarioBundle(
            scenarioVersionId = version.id,
            alarmDefinitionId = definition.id,
            alarmCode = definition.code,
            alarmName = definition.name,
            organizationId = definition.organizationId,
            version = version.version,
            taskGroups = taskGroups(versionId),
        )
    }

    fun taskGroups(versionId: UUID): List<TaskGroupDto> {
        val p = mapOf("v" to versionId)
        val tasks = jdbc.query(
            """
            SELECT t.* FROM task t JOIN task_group g ON g.id = t.task_group_id
            WHERE g.scenario_version_id = :v ORDER BY t.order_no
            """.trimIndent(), p,
        ) { rs, _ ->
            rs.getObject("task_group_id", UUID::class.java) to TaskDto(
                id = rs.getObject("id", UUID::class.java),
                orderNo = rs.getInt("order_no"),
                description = rs.getString("description"),
                requiresConfirmation = rs.getBoolean("requires_confirmation"),
            )
        }.groupBy({ it.first }, { it.second })
        val assignees = jdbc.query(
            """
            SELECT a.task_group_id, a.rank, p.id, p.display_name, p.role
            FROM assignment a JOIN person p ON p.id = a.person_id JOIN task_group g ON g.id = a.task_group_id
            WHERE g.scenario_version_id = :v ORDER BY a.rank, p.display_name
            """.trimIndent(), p,
        ) { rs, _ ->
            rs.getObject("task_group_id", UUID::class.java) to AssigneeDto(
                personId = rs.getObject("id", UUID::class.java),
                displayName = rs.getString("display_name"),
                role = rs.getString("role"),
                rank = rs.getInt("rank"),
            )
        }.groupBy({ it.first }, { it.second })
        return jdbc.query(
            """
            SELECT g.id, g.name, g.order_no, l.id AS loc_id, l.name AS loc_name, l.address,
                   ST_Y(l.geom::geometry) AS lat, ST_X(l.geom::geometry) AS lon
            FROM task_group g LEFT JOIN location l ON l.id = g.location_id
            WHERE g.scenario_version_id = :v ORDER BY g.order_no
            """.trimIndent(), p,
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val locId = rs.getObject("loc_id", UUID::class.java)
            TaskGroupDto(
                id = id,
                name = rs.getString("name"),
                orderNo = rs.getInt("order_no"),
                location = locId?.let {
                    LocationDto(it, rs.getString("loc_name"), rs.getString("address"), rs.getDouble("lat"), rs.getDouble("lon"))
                },
                assignees = assignees[id].orEmpty(),
                tasks = tasks[id].orEmpty(),
            )
        }
    }

    fun controllerRights(personId: UUID): List<ControllerRight> =
        jdbc.query(
            """
            SELECT c.alarm_definition_id, a.code, a.name, c.can_raise, c.can_cancel
            FROM alarm_controller c JOIN alarm_definition a ON a.id = c.alarm_definition_id
            WHERE c.person_id = :p ORDER BY a.name
            """.trimIndent(),
            mapOf("p" to personId),
        ) { rs, _ ->
            ControllerRight(
                alarmDefinitionId = rs.getObject("alarm_definition_id", UUID::class.java),
                code = rs.getString("code"),
                name = rs.getString("name"),
                canRaise = rs.getBoolean("can_raise"),
                canCancel = rs.getBoolean("can_cancel"),
            )
        }

    /** Current published scenario versions in which the person holds an assignment. */
    fun currentVersionsAssignedTo(personId: UUID): List<UUID> =
        jdbc.queryForList(
            """
            SELECT DISTINCT a.current_scenario_version_id
            FROM alarm_definition a
            JOIN task_group g ON g.scenario_version_id = a.current_scenario_version_id
            JOIN assignment s ON s.task_group_id = g.id
            WHERE s.person_id = :p
            """.trimIndent(),
            mapOf("p" to personId), UUID::class.java,
        )

    fun relayOverride(versionId: UUID, deviceClassId: Int): String? =
        jdbc.query(
            "SELECT relay_mode FROM relay_policy_override WHERE scenario_version_id = :v AND device_class_id = :c",
            mapOf("v" to versionId, "c" to deviceClassId),
        ) { rs, _ -> rs.getString(1) }.firstOrNull()
}
