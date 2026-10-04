package pl.siren.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import pl.siren.alarm.AlarmCommandException
import pl.siren.alarm.AlarmQueryService
import pl.siren.alarm.AlarmService
import pl.siren.alarm.EventRow
import pl.siren.alarm.InstanceDetail
import pl.siren.alarm.InstanceSummary
import pl.siren.alarm.IngestOutcome
import pl.siren.config.SirenProperties
import pl.siren.crypto.Hashing
import pl.siren.identity.Bearer
import pl.siren.identity.IdentityRepository
import pl.siren.live.LiveUpdates
import pl.siren.scenario.ScenarioRepository
import pl.siren.scenario.TaskGroupDto
import java.time.Instant
import java.util.UUID

data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val token: String, val username: String, val displayName: String, val organizationName: String)
data class RaiseRequest(val definitionId: UUID, val description: String? = null)
data class AlarmDefinitionView(
    val id: UUID,
    val code: String,
    val name: String,
    val organizationName: String,
    val domain: String,
    val version: Int?,
    val scenarioVersionId: UUID?,
    val groups: Int,
    val tasks: Int,
    val people: Int,
    val canRaise: Boolean,
    val canCancel: Boolean,
    val activeInstanceId: UUID?,
)
data class ScenarioView(
    val definition: AlarmDefinitionView,
    val versions: List<VersionView>,
    val taskGroups: List<TaskGroupDto>,
    val controllers: List<ControllerView>,
)
data class VersionView(val id: UUID, val version: Int, val status: String, val bundleObjectKey: String?, val bundleSha256: String?, val publishedAt: Instant?)
data class ControllerView(val personId: UUID, val displayName: String, val canRaise: Boolean, val canCancel: Boolean)
data class DeviceView(
    val id: UUID,
    val platform: String,
    val deviceClass: String,
    val criticality: String,
    val relayMode: String,
    val status: String,
    val lastSeenAt: Instant?,
    val lastSyncAt: Instant?,
    val bearers: List<Bearer>,
    val simulated: Boolean,
)
data class PersonView(
    val id: UUID,
    val displayName: String,
    val role: String?,
    val phone: String?,
    val organizationName: String,
    val domain: String,
    val devices: List<DeviceView>,
)
data class KeyEpochView(val id: Int, val organizationName: String, val status: String, val validFrom: Instant, val validTo: Instant, val publicKeySha256: String)

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val sessions: OperatorSessions,
    private val live: LiveUpdates,
    private val scenarios: ScenarioRepository,
    private val alarms: AlarmService,
    private val queries: AlarmQueryService,
    private val identity: IdentityRepository,
    private val jdbc: NamedParameterJdbcTemplate,
) {
    @PostMapping("/login")
    fun login(@RequestBody req: LoginRequest): ResponseEntity<Any> {
        val s = sessions.login(req.username.trim(), req.password)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Wrong username or password"))
        return ResponseEntity.ok(LoginResponse(s.token, s.username, s.displayName, s.organizationName))
    }

    @PostMapping("/logout")
    fun logout(@RequestAttribute(OperatorAuthFilter.SESSION_ATTR) s: OperatorSession) = sessions.logout(s.token)

    @GetMapping("/me")
    fun me(@RequestAttribute(OperatorAuthFilter.SESSION_ATTR) s: OperatorSession) =
        LoginResponse(s.token, s.username, s.displayName, s.organizationName)

    @GetMapping("/stream", produces = ["text/event-stream"])
    fun stream(): SseEmitter = live.subscribe()

    @GetMapping("/alarm-definitions")
    fun definitions(@RequestAttribute(OperatorAuthFilter.SESSION_ATTR) s: OperatorSession): List<AlarmDefinitionView> {
        val rights = scenarios.controllerRights(s.personId).associateBy { it.alarmDefinitionId }
        val active = queries.active().associateBy { it.definitionId }
        val counts = jdbc.query(
            """
            SELECT g.scenario_version_id, count(DISTINCT g.id) AS groups, count(DISTINCT t.id) AS tasks, count(DISTINCT a.person_id) AS people
            FROM task_group g LEFT JOIN task t ON t.task_group_id = g.id LEFT JOIN assignment a ON a.task_group_id = g.id
            GROUP BY g.scenario_version_id
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getObject(1, UUID::class.java) to Triple(rs.getInt(2), rs.getInt(3), rs.getInt(4)) }.toMap()
        return scenarios.definitions().map { d ->
            val c = d.currentScenarioVersionId?.let { counts[it] } ?: Triple(0, 0, 0)
            AlarmDefinitionView(
                id = d.id, code = d.code, name = d.name, organizationName = d.organizationName, domain = d.domain,
                version = d.currentVersion, scenarioVersionId = d.currentScenarioVersionId,
                groups = c.first, tasks = c.second, people = c.third,
                canRaise = rights[d.id]?.canRaise == true, canCancel = rights[d.id]?.canCancel == true,
                activeInstanceId = active[d.id]?.id,
            )
        }
    }

    @GetMapping("/scenarios/{definitionId}")
    fun scenario(
        @RequestAttribute(OperatorAuthFilter.SESSION_ATTR) s: OperatorSession,
        @PathVariable definitionId: UUID,
    ): ScenarioView {
        val definition = definitions(s).firstOrNull { it.id == definitionId } ?: throw NoSuchElementException("Unknown alarm")
        val versions = scenarios.versions(definitionId).map {
            VersionView(it.id, it.version, it.status, it.bundleObjectKey, it.bundleSha256?.let(Hashing::hex), it.publishedAt)
        }
        val controllers = jdbc.query(
            """
            SELECT p.id, p.display_name, c.can_raise, c.can_cancel FROM alarm_controller c JOIN person p ON p.id = c.person_id
            WHERE c.alarm_definition_id = :a ORDER BY p.display_name
            """.trimIndent(),
            mapOf("a" to definitionId),
        ) { rs, _ -> ControllerView(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)) }
        val groups = definition.scenarioVersionId?.let { scenarios.taskGroups(it) }.orEmpty()
        return ScenarioView(definition, versions, groups, controllers)
    }

    @PostMapping("/alarms")
    fun raise(
        @RequestAttribute(OperatorAuthFilter.SESSION_ATTR) s: OperatorSession,
        @RequestBody req: RaiseRequest,
    ): InstanceDetail {
        val result = alarms.raiseFromConsole(s.personId, s.consoleDeviceId, req.definitionId, req.description)
        if (result.outcome != IngestOutcome.ACCEPTED) throw AlarmCommandException(result.reason ?: "Alarm was not accepted")
        return queries.detail(result.instanceId!!)!!
    }

    @PostMapping("/alarms/{id}/cancel")
    fun cancel(
        @RequestAttribute(OperatorAuthFilter.SESSION_ATTR) s: OperatorSession,
        @PathVariable id: UUID,
    ): InstanceDetail {
        val result = alarms.cancelFromConsole(s.personId, s.consoleDeviceId, id)
        if (result.outcome == IngestOutcome.REJECTED) throw AlarmCommandException(result.reason ?: "Cancel was not accepted")
        return queries.detail(id)!!
    }

    @GetMapping("/alarms/active")
    fun active(): List<InstanceSummary> = queries.active()

    @GetMapping("/alarms/history")
    fun history(): List<InstanceSummary> = queries.history()

    @GetMapping("/alarms/{id}")
    fun detail(@PathVariable id: UUID): InstanceDetail = queries.detail(id) ?: throw NoSuchElementException("Unknown alarm")

    @GetMapping("/events")
    fun events(): List<EventRow> = queries.events()

    @GetMapping("/people")
    fun people(): List<PersonView> {
        val simulated = jdbc.queryForList("SELECT device_id FROM demo_simulated_device", emptyMap<String, Any>(), UUID::class.java).toSet()
        val syncs = jdbc.query("SELECT device_id, last_sync_at FROM device_sync_state", emptyMap<String, Any>()) { rs, _ ->
            rs.getObject(1, UUID::class.java) to rs.getTimestamp(2)?.toInstant()
        }.toMap()
        val devices = identity.devices().groupBy { it.personId }
        return identity.people().map { p ->
            PersonView(
                id = p.id, displayName = p.displayName, role = p.role, phone = p.phone,
                organizationName = p.organizationName, domain = p.domain,
                devices = devices[p.id].orEmpty().map { d ->
                    DeviceView(
                        id = d.id, platform = d.platform, deviceClass = d.deviceClassCode, criticality = d.criticality,
                        relayMode = d.relayModeOverride ?: d.defaultRelayMode, status = d.status,
                        lastSeenAt = d.lastSeenAt, lastSyncAt = syncs[d.id], bearers = identity.bearers(d.id),
                        simulated = d.id in simulated,
                    )
                },
            )
        }
    }

    @GetMapping("/keys")
    fun keys(): List<KeyEpochView> =
        jdbc.query(
            "SELECT k.*, o.name AS org_name FROM key_epoch k JOIN organization o ON o.id = k.organization_id ORDER BY o.name, k.id",
            emptyMap<String, Any>(),
        ) { rs, _ ->
            KeyEpochView(
                id = rs.getInt("id"), organizationName = rs.getString("org_name"), status = rs.getString("status"),
                validFrom = rs.getTimestamp("valid_from").toInstant(), validTo = rs.getTimestamp("valid_to").toInstant(),
                publicKeySha256 = Hashing.hex(Hashing.sha256(rs.getBytes("public_key"))).take(16),
            )
        }
}

@RestController
@RequestMapping("/api/public")
class PublicController(private val props: SirenProperties) {
    @GetMapping("/info")
    fun info() = mapOf(
        "name" to "Siren",
        "demo" to props.demo.enabled,
        "serverTime" to Instant.now(),
        "demoAccounts" to if (props.demo.enabled) listOf(
            mapOf("username" to "admin", "password" to "siren", "who" to "Katarzyna Nowak — Crisis Management Operator"),
            mapOf("username" to "hospital", "password" to "siren", "who" to "Dr Robert Krawczyk — Emergency Department Manager"),
            mapOf("username" to "army", "password" to "siren", "who" to "Capt. Adam Grabowski — company commander"),
        ) else emptyList(),
    )
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(AlarmCommandException::class)
    fun command(e: AlarmCommandException) = ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))
}
